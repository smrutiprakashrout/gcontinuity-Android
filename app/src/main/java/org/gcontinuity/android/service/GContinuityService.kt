package org.gcontinuity.android.service

import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.gcontinuity.android.identity.DeviceIdentity
import org.gcontinuity.android.identity.IdentityManager
import org.gcontinuity.android.network.DeviceInfo
import org.gcontinuity.android.network.mdns.MdnsDiscovery
import org.gcontinuity.android.network.mdns.MdnsWatchdog
import org.gcontinuity.android.network.reconnect.NetworkEvent
import org.gcontinuity.android.network.reconnect.NetworkWatcher
import org.gcontinuity.android.network.reconnect.ReconnectManager
import org.gcontinuity.android.network.ws.WsClient
import org.gcontinuity.android.pairing.PairingManager
import org.gcontinuity.android.pairing.PairingState
import org.gcontinuity.android.store.DeviceStore
import org.gcontinuity.android.transport.TransportManager
import org.gcontinuity.android.transport.model.ConnectionState
import org.gcontinuity.android.transport.tls.hexColonToByteArray
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow

private const val TAG = "GContinuityService"
private const val WAKELOCK_TAG = "gcontinuity:service"

/**
 * Foreground service that owns both the pairing/mDNS layer (Phase 1) and the
 * transport layer (Phase 2).
 *
 * **Pairing layer** (manually constructed, pre-Hilt):
 * - [IdentityManager] — device TLS identity
 * - [DeviceStore] — trusted device fingerprint storage
 * - [WsClient] + [PairingManager] — HELLO/PAIR handshake
 * - [MdnsDiscovery] — mDNS scanner
 * - [ReconnectManager] — reconnect scheduler for the pairing channel
 * - [NetworkWatcher] — WiFi loss/restore callback
 *
 * **Transport layer** (Hilt-injected, Phase 2):
 * - [TransportManager] — TLS WebSocket post-pairing session
 * - [NotificationHelper] — injectable notification builder
 *
 * When mDNS discovers a device that is already trusted (Phase 1 pairing complete),
 * [TransportManager.connect] is called so the Phase 2 session begins automatically.
 */
@AndroidEntryPoint
class GContinuityService : LifecycleService() {

    // ── Hilt-injected (Phase 2) ───────────────────────────────────────────────

    @Inject lateinit var transportManager: TransportManager
    @Inject lateinit var notificationHelper: NotificationHelper

    // ── Manually constructed (Phase 1 — pre-Hilt) ────────────────────────────

    private lateinit var identityManager: IdentityManager
    lateinit var identity: DeviceIdentity
    lateinit var store: DeviceStore
    private lateinit var mdns: MdnsDiscovery
    private lateinit var mdnsWatchdog: MdnsWatchdog
    private lateinit var networkWatcher: NetworkWatcher
    lateinit var wsClient: WsClient
    lateinit var pairingManager: PairingManager
    private lateinit var reconnectManager: ReconnectManager

    private var wakeLock: PowerManager.WakeLock? = null

    /** Last device for which pairing completed, retained across reconnects for the UI. */
    private var lastConnectedDevice: DeviceInfo? = null

    val pairingState    = MutableStateFlow<PairingState>(PairingState.Idle)
    val discoveredDevices = MutableStateFlow<List<DeviceInfo>>(emptyList())

    companion object {
        const val ACTION_DISCONNECT     = "org.gcontinuity.ACTION_DISCONNECT"
        const val ACTION_ACCEPT_PAIRING = "org.gcontinuity.ACCEPT_PAIRING"
        const val ACTION_REJECT_PAIRING = "org.gcontinuity.REJECT_PAIRING"

        /** Non-null while the service is running; null otherwise. */
        var instance: GContinuityService? = null
            private set
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "Service created")

        notificationHelper.createChannel(this)
        startForeground(
            NotificationHelper.NOTIFICATION_ID,
            notificationHelper.buildNotification(this, "Starting…"),
        )
        acquireWakeLock()

        // ── Phase 1 manual construction ───────────────────────────────────────
        identityManager = IdentityManager(this)
        identity        = identityManager.loadOrCreate(deviceName = Build.MODEL)
        store           = DeviceStore(this)

        wsClient = WsClient(
            scope            = lifecycleScope,
            store            = store,
            identity         = identity,
            onPacketReceived = { packet -> pairingManager.handlePacket(packet) },
            onConnected      = { notificationHelper.postUpdate(this, stateToText(pairingState.value)) },
            onDisconnected   = {
                val device = lastConnectedDevice
                if (device != null) {
                    pairingState.value = PairingState.Reconnecting(device)
                    notificationHelper.postUpdate(this, "Reconnecting…")
                } else {
                    pairingState.value = PairingState.Scanning
                    notificationHelper.postUpdate(this, "Scanning…")
                }
            },
        )

        pairingManager = PairingManager(
            identity    = identity,
            store       = store,
            wsClient    = wsClient,
            onStateChange = { state ->
                if (state is PairingState.PairedConnected) {
                    lastConnectedDevice = state.device
                }
                pairingState.value = state
                notificationHelper.postUpdate(this, stateToText(state))
            },
        )

        mdns = MdnsDiscovery(
            context    = this,
            scope      = lifecycleScope,
            deviceId   = identity.deviceId,
            deviceName = identity.deviceName,
            onDeviceFound = { device ->
                mdns.refreshTimestamp(device.deviceId)
                discoveredDevices.update { current ->
                    current.filterNot { it.deviceId == device.deviceId } + device
                }
                reconnectManager.onDeviceDiscovered(device)

                // Phase 2: start TransportManager when a trusted device appears on LAN
                if (store.isTrusted(device.deviceId)) {
                    val fp = store.getFingerprint(device.deviceId)
                    if (fp != null && device.host.isNotEmpty()) {
                        val certBytes = fp.hexColonToByteArray()
                        transportManager.connect(
                            scope      = lifecycleScope,
                            host       = device.host,
                            port       = device.port,
                            certSha256 = certBytes,
                        )
                    }
                }
            },
            onDeviceLost = { deviceId ->
                discoveredDevices.update { it.filterNot { d -> d.deviceId == deviceId } }
            },
        )

        reconnectManager = ReconnectManager(
            scope    = lifecycleScope,
            store    = store,
            wsClient = wsClient,
            mdns     = mdns,
        )

        mdnsWatchdog   = MdnsWatchdog(lifecycleScope, mdns)
        networkWatcher = NetworkWatcher(this)
        networkWatcher.register { event ->
            when (event) {
                is NetworkEvent.WiFiAvailable -> reconnectManager.onNetworkRestored()
                is NetworkEvent.WiFiLost      -> reconnectManager.onNetworkLost()
            }
        }

        mdns.start()
        mdnsWatchdog.start()
        pairingState.value = PairingState.Scanning
        notificationHelper.postUpdate(this, "Scanning…")

        // Stale-device cleanup every 30 s
        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000)
                val stale = mdns.getStaleDeviceIds(60_000)
                if (stale.isNotEmpty()) {
                    discoveredDevices.update { it.filterNot { d -> d.deviceId in stale } }
                }
            }
        }

        // Update notification when Phase 2 transport state changes
        transportManager.connectionState
            .onEach { state ->
                val text = when (state) {
                    ConnectionState.CONNECTED    ->
                        "Connected to ${transportManager.connectedDeviceName.value ?: "Linux"}"
                    ConnectionState.RECONNECTING -> "Reconnecting…"
                    ConnectionState.CONNECTING   -> "Connecting…"
                    ConnectionState.DISCONNECTED -> stateToText(pairingState.value)
                }
                notificationHelper.postUpdate(this, text)
            }
            .launchIn(lifecycleScope)

        Log.i(TAG, "Service initialized. Device ID: ${identity.deviceId}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                lastConnectedDevice = null
                transportManager.stop()
                wsClient.disconnect()
            }
            ACTION_ACCEPT_PAIRING -> {
                val state = pairingState.value
                if (state is PairingState.AwaitingPair) {
                    pairingManager.acceptPairing(state.device)
                }
            }
            ACTION_REJECT_PAIRING -> pairingManager.rejectPairing()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "Task removed — scheduling restart")
        val restart = Intent(applicationContext, GContinuityService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(restart)
        } else {
            applicationContext.startService(restart)
        }
    }

    override fun onDestroy() {
        instance = null
        mdnsWatchdog.stop()
        mdns.stop()
        networkWatcher.unregister()
        reconnectManager.cancelReconnect()
        wsClient.disconnect("service_stopping")
        transportManager.stop()
        releaseWakeLock()
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun stateToText(state: PairingState): String = when (state) {
        is PairingState.PairedConnected -> "Connected to ${state.device.name}"
        is PairingState.AwaitingPair    -> "Pairing with ${state.device.name}…"
        is PairingState.Reconnecting    -> "Reconnecting to ${state.device.name}…"
        is PairingState.Scanning        -> "Scanning…"
        is PairingState.Error           -> "Error: ${state.message}"
        else                            -> "Idle"
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).also {
            it.acquire()
        }
        Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        Log.d(TAG, "WakeLock released")
    }

    fun triggerRefresh() {
        discoveredDevices.value = emptyList()
        lifecycleScope.launch { mdns.clearAndRescan() }
    }
}

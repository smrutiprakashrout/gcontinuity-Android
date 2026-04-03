package org.gcontinuity.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.gcontinuity.android.MainActivity
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

class GContinuityService : Service() {

    companion object {
        const val NOTIF_CHANNEL_ID = "gcontinuity_service"
        const val NOTIF_ID = 1001
        const val ACTION_DISCONNECT = "org.gcontinuity.DISCONNECT"
        const val ACTION_ACCEPT_PAIRING = "org.gcontinuity.ACCEPT_PAIRING"
        const val ACTION_REJECT_PAIRING = "org.gcontinuity.REJECT_PAIRING"
        private const val TAG = "GContinuityService"

        var instance: GContinuityService? = null
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var identityManager: IdentityManager
    lateinit var identity: DeviceIdentity
    private lateinit var store: DeviceStore
    private lateinit var mdns: MdnsDiscovery
    private lateinit var mdnsWatchdog: MdnsWatchdog
    private lateinit var networkWatcher: NetworkWatcher
    lateinit var wsClient: WsClient
    lateinit var pairingManager: PairingManager
    private lateinit var reconnectManager: ReconnectManager

    val pairingState = MutableStateFlow<PairingState>(PairingState.Idle)
    val discoveredDevices = MutableStateFlow<List<DeviceInfo>>(emptyList())

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "Service created")

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Starting..."))

        identityManager = IdentityManager(this)
        identity = identityManager.loadOrCreate(deviceName = Build.MODEL)
        store = DeviceStore(this)

        wsClient = WsClient(
            scope = serviceScope,
            store = store,
            identity = identity,
            onPacketReceived = { packet -> pairingManager.handlePacket(packet) },
            onConnected = { updateNotification("Connected") },
            onDisconnected = {
                pairingState.value = PairingState.Reconnecting
                updateNotification("Reconnecting...")
            }
        )

        pairingManager = PairingManager(
            identity = identity,
            store = store,
            wsClient = wsClient,
            onStateChange = { state ->
                pairingState.value = state
                updateNotification(stateToNotifText(state))
            }
        )

        mdns = MdnsDiscovery(
            context = this,
            scope = serviceScope,
            deviceId = identity.deviceId,
            deviceName = identity.deviceName,
            onDeviceFound = { device ->
                discoveredDevices.update { current ->
                    (current.filterNot { it.deviceId == device.deviceId } + device)
                }
                reconnectManager.onDeviceDiscovered(device)
            },
            onDeviceLost = { deviceId ->
                discoveredDevices.update { it.filterNot { d -> d.deviceId == deviceId } }
            }
        )

        reconnectManager = ReconnectManager(
            scope = serviceScope,
            store = store,
            wsClient = wsClient,
            mdns = mdns
        )

        mdnsWatchdog = MdnsWatchdog(serviceScope, mdns)

        networkWatcher = NetworkWatcher(this)
        networkWatcher.register { event ->
            when (event) {
                is NetworkEvent.WiFiAvailable -> reconnectManager.onNetworkRestored()
                is NetworkEvent.WiFiLost -> reconnectManager.onNetworkLost()
            }
        }

        mdns.start()
        mdnsWatchdog.start()
        pairingState.value = PairingState.Scanning
        updateNotification("Scanning...")
        Log.i(TAG, "Service initialized. Device ID: ${identity.deviceId}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> wsClient.disconnect()
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

    override fun onDestroy() {
        instance = null
        mdnsWatchdog.stop()
        mdns.stop()
        networkWatcher.unregister()
        reconnectManager.cancelReconnect()
        wsClient.disconnect("service_stopping")
        serviceScope.let {
            val job = it.coroutineContext[kotlinx.coroutines.Job]
            job?.cancel()
        }
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "GContinuity",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Device connection status" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val mainIntent = Intent(this, MainActivity::class.java)
        val contentIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, GContinuityService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPending = PendingIntent.getService(
            this, 1, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("GContinuity")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", disconnectPending)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun stateToNotifText(state: PairingState): String = when (state) {
        is PairingState.PairedConnected -> "Connected to ${state.device.name}"
        is PairingState.AwaitingPair -> "Pairing with ${state.device.name}..."
        is PairingState.Reconnecting -> "Reconnecting..."
        is PairingState.Scanning -> "Scanning..."
        is PairingState.Error -> "Error: ${state.message}"
        else -> "Idle"
    }
}

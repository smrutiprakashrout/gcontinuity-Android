package org.gcontinuity.android.plugins

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.gcontinuity.android.transport.model.Packet
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG              = "BatteryPlugin"
private const val POLL_INTERVAL_MS = 15_000L

/**
 * Phase 3 feature plugin — bidirectional battery status sync.
 *
 * ## Android → Linux (outbound)
 * Sends [Packet.BatteryInfo] via [wsClientSender] — the Phase 1 WsClient
 * socket. This is the ONLY socket that reliably connects to the daemon.
 * TransportManager (Phase 2) is NOT used here because it never successfully
 * completes its handshake with the daemon.
 *
 * [wsClientSender] is a raw-JSON lambda set by [GContinuityService] after
 * WsClient is constructed. It calls [WsClient.sendRaw].
 *
 * ## Linux → Android (inbound)
 * [onPacket] receives [Packet.LinuxBatteryInfo] forwarded by [PluginManager]
 * and updates [linuxBatteryState].
 */
@Singleton
class BatteryPlugin @Inject constructor(
    @ApplicationContext private val context: Context,
) : FeaturePlugin {

    override val pluginId = "battery_report"

    /** Set by GContinuityService after WsClient is constructed. */
    var wsClientSender: ((String) -> Unit)? = null

    private val json = Json {
        ignoreUnknownKeys  = true
        encodeDefaults     = true
        classDiscriminator = "type"
    }

    // ── Outbound state ────────────────────────────────────────────────────────

    private val _batteryState = MutableStateFlow<BatteryState?>(null)
    val batteryState: StateFlow<BatteryState?> = _batteryState.asStateFlow()

    // ── Inbound state (Linux battery) ─────────────────────────────────────────

    private val _linuxBatteryState = MutableStateFlow<BatteryState?>(null)
    /**
     * Linux machine's battery — updated when [Packet.LinuxBatteryInfo] arrives.
     * Displayed in ConnectedScreen TopAppBar below "Connected".
     */
    val linuxBatteryState: StateFlow<BatteryState?> = _linuxBatteryState.asStateFlow()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    private var pollJob: Job? = null
    private var pluginScope: CoroutineScope? = null

    override fun start(scope: CoroutineScope) {
        pluginScope = scope
        Log.i(TAG, "Starting battery poll (interval=${POLL_INTERVAL_MS}ms)")
        pollJob = scope.launch {
            while (true) {
                pollAndSend()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    override fun stop() {
        pollJob?.cancel()
        pollJob = null
        pluginScope = null
        _linuxBatteryState.value = null
        Log.i(TAG, "Stopped")
    }

    /**
     * Send current Android battery immediately — called by [PluginManager.sendBatteryNow]
     * when pairing completes so the daemon and Flutter see real data instantly.
     */
    fun sendNow() {
        pluginScope?.launch { pollAndSend() }
            ?: Log.w(TAG, "sendNow: plugin not started yet")
    }

    // ── Inbound ───────────────────────────────────────────────────────────────

    override suspend fun onPacket(packet: Packet) {
        if (packet is Packet.LinuxBatteryInfo) {
            Log.d(TAG, "RX LinuxBatteryInfo: ${packet.percent}% charging=${packet.isCharging}")
            _linuxBatteryState.value = BatteryState(
                percent    = packet.percent,
                isCharging = packet.isCharging,
                timestamp  = packet.timestamp,
            )
        }
    }

    // ── Outbound ──────────────────────────────────────────────────────────────

    private fun pollAndSend() {
        val sender = wsClientSender ?: run {
            Log.w(TAG, "wsClientSender not set yet — skipping poll")
            return
        }

        val intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        ) ?: run {
            Log.w(TAG, "Battery intent null — skipping poll")
            return
        }

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) {
            Log.w(TAG, "Invalid battery reading (level=$level scale=$scale) — skipping")
            return
        }

        val statusInt  = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = statusInt == BatteryManager.BATTERY_STATUS_CHARGING ||
                statusInt == BatteryManager.BATTERY_STATUS_FULL
        val percent    = level * 100 / scale
        val now        = System.currentTimeMillis()

        _batteryState.value = BatteryState(
            percent    = percent,
            isCharging = isCharging,
            timestamp  = now,
        )

        // Serialize transport.model.Packet.BatteryInfo to JSON and send via
        // WsClient.sendRaw() — the Phase 1 socket that reliably connects.
        val packet = Packet.BatteryInfo(
            percent    = percent,
            isCharging = isCharging,
            timestamp  = now,
        )
        val encoded = json.encodeToString(Packet.serializer(), packet)
        sender(encoded)
        Log.d(TAG, "TX BatteryInfo via WsClient: $percent% charging=$isCharging")
    }
}
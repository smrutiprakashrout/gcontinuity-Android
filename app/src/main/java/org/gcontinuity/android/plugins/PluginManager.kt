package org.gcontinuity.android.plugins

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.gcontinuity.android.store.PluginStore
import org.gcontinuity.android.transport.PacketHandler
import org.gcontinuity.android.transport.model.Packet
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PluginManager"

/**
 * Manages lifecycle and inbound packet routing for all [FeaturePlugin] instances.
 *
 * ## Registered plugins (Phase 3)
 * - [BatteryPlugin]   — battery sync (Phase 3.1)
 * - [ClipboardPlugin] — clipboard sync (Phase 3.2)
 *
 * ## Sending over Phase 1 socket
 * Both [BatteryPlugin] and [ClipboardPlugin] send packets via [WsClient.sendRaw]
 * (Phase 1 socket — the only socket that reliably connects to the daemon).
 * [GContinuityService] calls [setWsClientSender] after constructing WsClient,
 * which propagates the sender lambda to both plugins.
 *
 * ## Two inbound paths
 * - Path A: TransportManager → PacketHandler.featurePackets → plugins
 * - Path B: WsClient → GContinuityService → [dispatchRawFeaturePacket] → plugins
 *
 * ## Adding future plugins
 * Add one constructor param + one line in [start]'s `all` list.
 * [GContinuityService] never changes.
 */
@Singleton
class PluginManager @Inject constructor(
    private val pluginStore: PluginStore,
    private val packetHandler: PacketHandler,
    private val batteryPlugin:   BatteryPlugin,
    private val clipboardPlugin: ClipboardPlugin,
    // private val notificationsPlugin: NotificationsPlugin,  // Phase 4
) {
    private var enabledPlugins: List<FeaturePlugin> = emptyList()
    private var pluginScope: CoroutineScope? = null

    private val json = Json {
        ignoreUnknownKeys  = true
        isLenient          = true
        encodeDefaults     = true
        classDiscriminator = "type"
    }

    // ── UI state passthroughs ─────────────────────────────────────────────────

    val batteryState: StateFlow<BatteryState?>      = batteryPlugin.batteryState
    val linuxBatteryState: StateFlow<BatteryState?> = batteryPlugin.linuxBatteryState

    // ── WsClient sender wiring ────────────────────────────────────────────────

    /**
     * Called by [GContinuityService] immediately after WsClient is constructed.
     * Propagates the raw-JSON sender to every plugin that needs to send packets
     * over the Phase 1 socket.
     */
    fun setWsClientSender(sender: (String) -> Unit) {
        batteryPlugin.wsClientSender   = sender
        clipboardPlugin.wsClientSender = sender
        Log.d(TAG, "WsClient sender set on BatteryPlugin + ClipboardPlugin")
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start(scope: CoroutineScope) {
        pluginScope = scope

        val all: List<FeaturePlugin> = listOf(
            batteryPlugin,
            clipboardPlugin,
            // notificationsPlugin,
        )

        // ClipboardPlugin uses two plugin IDs (send + receive) — check both.
        // For all others, isEnabled(pluginId) is sufficient.
        // ClipboardPlugin.start() internally checks both IDs itself, so we
        // include it in enabledPlugins as long as either direction is on.
        enabledPlugins = all.filter { plugin ->
            when (plugin) {
                is ClipboardPlugin ->
                    pluginStore.isEnabled("clipboard_sync_send") ||
                            pluginStore.isEnabled("clipboard_sync_receive")
                else ->
                    pluginStore.isEnabled(plugin.pluginId)
            }
        }

        enabledPlugins.forEach { plugin ->
            Log.i(TAG, "Starting plugin: ${plugin.pluginId}")
            plugin.start(scope)
        }

        // Path A: TransportManager → PacketHandler.featurePackets → plugins.
        packetHandler.featurePackets
            .onEach { packet -> dispatchToPlugins(packet) }
            .launchIn(scope)

        Log.i(TAG, "PluginManager started — active: ${enabledPlugins.map { it.pluginId }}")
    }

    fun stop() {
        enabledPlugins.forEach { plugin ->
            Log.i(TAG, "Stopping plugin: ${plugin.pluginId}")
            plugin.stop()
        }
        enabledPlugins = emptyList()
        pluginScope = null
    }

    // ── Convenience methods ───────────────────────────────────────────────────

    /** Trigger immediate Android battery send on pairing complete. */
    fun sendBatteryNow() {
        batteryPlugin.sendNow()
    }

    /**
     * Called by [MainActivity] when the user taps "Send Clipboard" from the
     * heads-up notification (Path B — app just came to foreground).
     */
    fun sendClipboardNow() {
        clipboardPlugin.sendClipboardNow()
    }

    // ── Path B: WsClient raw JSON dispatch ────────────────────────────────────

    fun dispatchRawFeaturePacket(rawJson: String) {
        val scope = pluginScope ?: run {
            Log.w(TAG, "dispatchRawFeaturePacket: not started yet — ignored")
            return
        }
        scope.launch {
            try {
                val packet = json.decodeFromString(Packet.serializer(), rawJson)
                dispatchToPlugins(packet)
            } catch (e: Exception) {
                Log.w(TAG, "Unrecognised feature packet, skipping: ${e.message}")
            }
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private suspend fun dispatchToPlugins(packet: Packet) {
        enabledPlugins.forEach { plugin -> plugin.onPacket(packet) }
    }
}
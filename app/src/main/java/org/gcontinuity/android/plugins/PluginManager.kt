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
 * ## Sending Android battery to Linux
 * Battery packets are sent via WsClient (Phase 1 socket) not TransportManager.
 * [GContinuityService] calls [setWsClientSender] after constructing WsClient,
 * which sets the raw-JSON sender lambda on [BatteryPlugin].
 *
 * ## Two inbound paths
 * - Path A: TransportManager → PacketHandler.featurePackets → plugins
 * - Path B: WsClient → GContinuityService → [dispatchRawFeaturePacket] → plugins
 */
@Singleton
class PluginManager @Inject constructor(
    private val pluginStore: PluginStore,
    private val packetHandler: PacketHandler,
    private val batteryPlugin: BatteryPlugin,
    // private val clipboardPlugin: ClipboardPlugin,
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
     * Called by [GContinuityService] after WsClient is constructed.
     * Sets the raw-JSON send lambda on [BatteryPlugin] so it sends
     * [Packet.BatteryInfo] over the Phase 1 socket instead of TransportManager.
     *
     * @param sender Lambda that calls [WsClient.sendRaw].
     */
    fun setWsClientSender(sender: (String) -> Unit) {
        batteryPlugin.wsClientSender = sender
        Log.d(TAG, "WsClient sender set on BatteryPlugin")
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start(scope: CoroutineScope) {
        pluginScope = scope

        val all: List<FeaturePlugin> = listOf(
            batteryPlugin,
            // clipboardPlugin,
        )

        enabledPlugins = all.filter { pluginStore.isEnabled(it.pluginId) }

        enabledPlugins.forEach { plugin ->
            Log.i(TAG, "Starting plugin: ${plugin.pluginId}")
            plugin.start(scope)
        }

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

    // ── Immediate battery send on pairing complete ────────────────────────────

    fun sendBatteryNow() {
        batteryPlugin.sendNow()
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
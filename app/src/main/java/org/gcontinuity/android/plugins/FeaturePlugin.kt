package org.gcontinuity.android.plugins

import kotlinx.coroutines.CoroutineScope
import org.gcontinuity.android.transport.model.Packet

/**
 * Contract for all GContinuity feature plugins.
 *
 * ## Lifecycle
 * [start] is called by [PluginManager] when the service starts, only if
 * [org.gcontinuity.android.store.PluginStore.isEnabled] returns true for [pluginId].
 * [stop] is called when the service is destroyed.
 *
 * ## Inbound packets
 * [PluginManager] forwards every packet from
 * [org.gcontinuity.android.transport.PacketHandler.featurePackets] to [onPacket].
 * Outbound-only plugins (e.g. [BatteryPlugin]) leave [onPacket] as the default no-op.
 * Inbound or bidirectional plugins filter for their own packet types inside [onPacket].
 *
 * ## Plugin ID
 * [pluginId] must exactly match the corresponding `id` field in
 * [org.gcontinuity.android.store.ALL_PLUGINS]. This is what [PluginManager] passes to
 * [org.gcontinuity.android.store.PluginStore.isEnabled] to decide whether to start the plugin.
 *
 * ## Adding a new plugin
 * 1. Create a class implementing this interface.
 * 2. Add it to [PluginManager]'s constructor and `all` list.
 * [org.gcontinuity.android.service.GContinuityService] never needs to change.
 */
interface FeaturePlugin {
    /** Must exactly match the corresponding ID in [org.gcontinuity.android.store.ALL_PLUGINS]. */
    val pluginId: String

    /** Start all background work (coroutines, receivers, observers). */
    fun start(scope: CoroutineScope)

    /** Cancel all background work and release all resources. */
    fun stop()

    /**
     * Called for every packet emitted on
     * [org.gcontinuity.android.transport.PacketHandler.featurePackets].
     * Default is a no-op — outbound-only plugins do not need to override this.
     */
    suspend fun onPacket(packet: Packet) {}
}
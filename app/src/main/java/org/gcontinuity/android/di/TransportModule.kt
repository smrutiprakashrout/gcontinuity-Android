package org.gcontinuity.android.di

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.gcontinuity.android.transport.PacketHandler
import org.gcontinuity.android.transport.TransportManager
import org.gcontinuity.android.transport.webrtc.WebRtcManager
import javax.inject.Singleton

/**
 * Hilt module that wires the Phase 2 transport layer singleton graph.
 *
 * **Circular dependency**: [TransportManager] → [PacketHandler] → [WebRtcManager]
 * → [TransportManager] (for sending WebRTC signaling packets back).
 *
 * **Resolution**: [WebRtcManager] exposes a `lateinit var transportManager` that is
 * set here in [provideTransportManager] after both objects have been constructed.
 * This is the standard pattern for Hilt circular deps when neither side can be broken
 * into an interface without significant added complexity.
 */
@Module
@InstallIn(SingletonComponent::class)
object TransportModule {

    /**
     * Provides the [WebRtcManager] singleton.
     * Constructed first so [PacketHandler] can reference it.
     */
    @Provides
    @Singleton
    fun provideWebRtcManager(
        @ApplicationContext context: Context,
    ): WebRtcManager = WebRtcManager(context)

    /**
     * Provides the [PacketHandler] singleton.
     * Depends on [WebRtcManager]; constructed second.
     */
    @Provides
    @Singleton
    fun providePacketHandler(
        webRtcManager: WebRtcManager,
    ): PacketHandler = PacketHandler(webRtcManager)

    /**
     * Provides the [TransportManager] singleton and resolves the circular dependency
     * by setting [WebRtcManager.transportManager] immediately after construction.
     */
    @Provides
    @Singleton
    fun provideTransportManager(
        @ApplicationContext context: Context,
        packetHandler: PacketHandler,
        webRtcManager: WebRtcManager,
    ): TransportManager = TransportManager(
        context       = context,
        packetHandler = packetHandler,
    ).also { tm ->
        // Break circular dep: WebRtcManager needs TransportManager to send signaling
        // packets back over the WebSocket. Setting it here after construction is safe
        // because no packets can arrive before the WebSocket session is established.
        webRtcManager.transportManager = tm
    }
}

package org.gcontinuity.android.transport

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.gcontinuity.android.transport.model.Packet
import org.gcontinuity.android.transport.webrtc.WebRtcManager
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PacketHandler"

/**
 * Routes incoming [Packet] values from [TransportManager] to the correct subsystem.
 *
 * **Phase 2 responsibility**: handles all WebRTC signaling packets and file-transfer
 * acknowledgements directly by delegating to [WebRtcManager].
 *
 * **Phase 3–6 extension point**: all feature packets (clipboard, battery, notifications,
 * media, input) are forwarded via [featurePackets] as a [SharedFlow]. Feature modules
 * added in later phases collect from this flow without modifying this class, keeping
 * [PacketHandler] a stable hub that never needs to be edited again.
 */
@Singleton
class PacketHandler @Inject constructor(
    private val webRtcManager: WebRtcManager,
) {
    /**
     * Feature packets destined for Phase 3–6 modules.
     * Backed by a buffer of 64 to absorb bursts without dropping packets
     * when a subscriber is briefly suspended.
     */
    private val _featurePackets = MutableSharedFlow<Packet>(extraBufferCapacity = 64)
    val featurePackets: SharedFlow<Packet> = _featurePackets.asSharedFlow()

    /**
     * Routes [packet] to the appropriate handler based on type.
     * Must be called from a coroutine (suspend for WebRTC async operations).
     */
    suspend fun handle(packet: Packet) {
        Log.d(TAG, "Routing: ${packet::class.simpleName}")
        when (packet) {

            // ── WebRTC signaling ──────────────────────────────────────────────

            is Packet.WebRtcSdpOffer -> {
                webRtcManager.handleSdpOffer(packet.session_id, packet.sdp)
            }
            is Packet.WebRtcSdpAnswer -> {
                webRtcManager.handleSdpAnswer(packet.session_id, packet.sdp)
            }
            is Packet.WebRtcIceCandidate -> {
                webRtcManager.handleIceCandidate(
                    sessionId     = packet.session_id,
                    candidate     = packet.candidate,
                    sdpMid        = packet.sdp_mid,
                    sdpMLineIndex = packet.sdp_m_line_index,
                )
            }
            is Packet.WebRtcClose -> {
                webRtcManager.closeSession(packet.session_id)
            }

            // ── File transfer control ─────────────────────────────────────────

            is Packet.FileSendAccept -> webRtcManager.onFileSendAccepted(packet.file_id)
            is Packet.FileSendReject -> webRtcManager.onFileSendRejected(packet.file_id)
            is Packet.FileSendEof    -> webRtcManager.onFileSendEof(packet.file_id, packet.sha256)

            // ── Everything else → feature layer (Phase 3–6) ───────────────────

            else -> {
                val emitted = _featurePackets.tryEmit(packet)
                if (!emitted) {
                    Log.w(TAG, "featurePackets buffer full — dropped ${packet::class.simpleName}")
                }
            }
        }
    }
}

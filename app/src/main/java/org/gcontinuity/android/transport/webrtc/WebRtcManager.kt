package org.gcontinuity.android.transport.webrtc

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.gcontinuity.android.transport.TransportManager
import org.gcontinuity.android.transport.model.Packet
import org.gcontinuity.android.transport.model.TransportState
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WebRtcManager"

/**
 * Manages WebRTC [PeerConnection] instances for file transfer and media streaming.
 *
 * One [PeerConnection] is created per concurrent session (keyed by [session_id]).
 * SDP negotiation and ICE signaling are relayed through [TransportManager] (WebSocket).
 *
 * **Circular dependency resolution**: [TransportManager] → [PacketHandler] →
 * [WebRtcManager] → [TransportManager]. Resolved by [lateinit var transportManager]
 * set in [TransportModule] after construction.
 */
@Singleton
class WebRtcManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Back-reference to [TransportManager] set by [TransportModule] after construction
     * to break the circular dependency. Guaranteed to be set before any packet arrives.
     */
    lateinit var transportManager: TransportManager

    private val eglBase: EglBase = EglBase.create()
    private val factory: PeerConnectionFactory by lazy { buildFactory() }

    /** Active WebRTC sessions keyed by session_id. */
    private val sessions = ConcurrentHashMap<String, WebRtcSession>()

    /** Active file transfers keyed by file_id. */
    private val fileTransfers = ConcurrentHashMap<String, FileTransferSession>()

    private val _webRtcState = MutableStateFlow<Map<String, TransportState>>(emptyMap())
    /** Per-session WebRTC connection state observable by the UI layer. */
    val webRtcState: StateFlow<Map<String, TransportState>> = _webRtcState.asStateFlow()

    // ── Factory ───────────────────────────────────────────────────────────────

    private fun buildFactory(): PeerConnectionFactory {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        return PeerConnectionFactory.builder()
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            )
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    // ── Signaling handlers called by PacketHandler ────────────────────────────

    /**
     * Handles an SDP offer from Linux. Android acts as the answerer:
     * creates a [PeerConnection], sets the remote description, then creates and
     * sends an SDP answer back through [TransportManager].
     */
    suspend fun handleSdpOffer(sessionId: String, sdp: String) {
        Log.d(TAG, "SDP offer for session $sessionId")
        val session = getOrCreateSession(sessionId)
        session.peerConnection.setRemoteDescription(
            SimpleSdpObserver("setRemoteDescription"),
            SessionDescription(SessionDescription.Type.OFFER, sdp),
        )
        session.peerConnection.createAnswer(
            object : SimpleSdpObserver("createAnswer") {
                override fun onCreateSuccess(desc: SessionDescription) {
                    session.peerConnection.setLocalDescription(
                        SimpleSdpObserver("setLocalDescription"), desc
                    )
                    transportManager.sendPacket(
                        Packet.WebRtcSdpAnswer(session_id = sessionId, sdp = desc.description)
                    )
                }
            },
            MediaConstraints(),
        )
    }

    /**
     * Handles an SDP answer from Linux (Android was the offerer in file-send flow).
     */
    fun handleSdpAnswer(sessionId: String, sdp: String) {
        Log.d(TAG, "SDP answer for session $sessionId")
        sessions[sessionId]?.peerConnection?.setRemoteDescription(
            SimpleSdpObserver("setRemoteDescription"),
            SessionDescription(SessionDescription.Type.ANSWER, sdp),
        )
    }

    /** Adds a remote ICE candidate to the named session. */
    fun handleIceCandidate(
        sessionId: String,
        candidate: String,
        sdpMid: String,
        sdpMLineIndex: Int,
    ) {
        sessions[sessionId]?.peerConnection?.addIceCandidate(
            IceCandidate(sdpMid, sdpMLineIndex, candidate)
        )
    }

    /** Closes a session and removes it from the active session map. */
    fun closeSession(sessionId: String) {
        sessions.remove(sessionId)?.peerConnection?.close()
        updateState(sessionId, TransportState.IDLE)
        Log.i(TAG, "Session $sessionId closed")
    }

    // ── File transfer ─────────────────────────────────────────────────────────

    /**
     * Initiates sending a file identified by [fileId] and located at [uri].
     * Creates a WebRTC DataChannel session and sends an SDP offer to Linux.
     */
    fun sendFile(fileId: String, uri: Uri) {
        val sessionId = "file-$fileId"
        val session   = getOrCreateSession(sessionId)
        val sender    = FileTransferSession.Sender(
            fileId           = fileId,
            peerConnection   = session.peerConnection,
            transportManager = transportManager,
            context          = context,
        )
        fileTransfers[fileId] = sender
        sender.initiate(uri)
        createOffer(sessionId)
    }

    /** Called when Linux accepts a file offer. DataChannel send will begin once ICE connects. */
    fun onFileSendAccepted(fileId: String) {
        Log.d(TAG, "File send accepted: $fileId")
    }

    /** Called when Linux rejects a file offer — clean up session and transfers. */
    fun onFileSendRejected(fileId: String) {
        Log.w(TAG, "File send rejected: $fileId")
        fileTransfers.remove(fileId)
        closeSession("file-$fileId")
    }

    /**
     * Called when the EOF WebSocket packet arrives for a receiving transfer.
     * Delegates SHA-256 verification and MediaStore write to [FileTransferSession.Receiver].
     */
    fun onFileSendEof(fileId: String, sha256: String) {
        (fileTransfers[fileId] as? FileTransferSession.Receiver)?.onEof(sha256)
    }

    // ── Session management ────────────────────────────────────────────────────

    private fun getOrCreateSession(sessionId: String): WebRtcSession =
        sessions.getOrPut(sessionId) {
            val config = PeerConnection.RTCConfiguration(emptyList()).apply {
                bundlePolicy  = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                // No STUN servers needed — LAN-only connection
            }
            val pc = factory.createPeerConnection(config, buildObserver(sessionId))
                ?: error("Failed to create PeerConnection for session $sessionId")
            updateState(sessionId, TransportState.SIGNALING)
            WebRtcSession(sessionId = sessionId, peerConnection = pc)
        }

    private fun createOffer(sessionId: String) {
        val session = sessions[sessionId] ?: return
        session.peerConnection.createOffer(
            object : SimpleSdpObserver("createOffer") {
                override fun onCreateSuccess(desc: SessionDescription) {
                    session.peerConnection.setLocalDescription(
                        SimpleSdpObserver("setLocalDescription"), desc
                    )
                    transportManager.sendPacket(
                        Packet.WebRtcSdpOffer(session_id = sessionId, sdp = desc.description)
                    )
                }
            },
            MediaConstraints(),
        )
    }

    private fun buildObserver(sessionId: String) = object : PeerConnection.Observer {

        override fun onIceCandidate(candidate: IceCandidate) {
            transportManager.sendPacket(
                Packet.WebRtcIceCandidate(
                    session_id      = sessionId,
                    candidate       = candidate.sdp,
                    sdp_mid         = candidate.sdpMid,
                    sdp_m_line_index = candidate.sdpMLineIndex,
                )
            )
        }

        override fun onDataChannel(dc: DataChannel) {
            Log.d(TAG, "DataChannel '${dc.label()}' opened for session $sessionId")
            val fileId   = sessionId.removePrefix("file-")
            val receiver = FileTransferSession.Receiver(
                fileId           = fileId,
                dataChannel      = dc,
                transportManager = transportManager,
                context          = context,
            )
            fileTransfers[fileId] = receiver
            receiver.attach()
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            val mapped = when (state) {
                PeerConnection.PeerConnectionState.CONNECTING -> TransportState.ICE_CHECKING
                PeerConnection.PeerConnectionState.CONNECTED  -> TransportState.ACTIVE
                PeerConnection.PeerConnectionState.FAILED     -> TransportState.FAILED
                PeerConnection.PeerConnectionState.CLOSED     -> TransportState.IDLE
                else                                          -> TransportState.SIGNALING
            }
            updateState(sessionId, mapped)
        }

        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            Log.d(TAG, "Remote track received for session $sessionId")
        }

        // Required overrides — no-op in Phase 2 (LAN, no STUN, DataChannel only)
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
        override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
        override fun onAddStream(s: MediaStream?) {}
        override fun onRemoveStream(s: MediaStream?) {}
        override fun onRenegotiationNeeded() {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
    }

    private fun updateState(sessionId: String, state: TransportState) {
        _webRtcState.value = _webRtcState.value.toMutableMap().also { it[sessionId] = state }
    }
}

/** Lightweight holder for a named [PeerConnection]. */
data class WebRtcSession(val sessionId: String, val peerConnection: PeerConnection)

/**
 * Minimal [SdpObserver] implementation with error logging.
 * Override [onCreateSuccess] when you need to act on the created description.
 */
open class SimpleSdpObserver(private val tag: String) : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) {}
    override fun onSetSuccess() { Log.d("SdpObserver", "$tag → onSetSuccess") }
    override fun onCreateFailure(error: String?) { Log.e("SdpObserver", "$tag → onCreateFailure: $error") }
    override fun onSetFailure(error: String?)    { Log.e("SdpObserver", "$tag → onSetFailure: $error") }
}

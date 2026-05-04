package org.gcontinuity.android.transport.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject

/**
 * Sealed class representing every packet type in the GContinuity protocol.
 *
 * Serialized as JSON with a "type" discriminator field. Must stay in sync with
 * the Rust [Packet] enum in gcontinuity-daemon. All discriminator values are
 * lowercase snake_case to match the Rust #[serde(rename = "...")] annotations.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed class Packet {

    // ── Handshake ─────────────────────────────────────────────────────────────

    /** Initial greeting sent by both sides after TLS handshake completes. */
    @Serializable
    @SerialName("hello")
    data class Hello(
        val device_id: String,
        val name: String,
        val version: Int,
    ) : Packet()

    /** Acknowledgement that Hello was accepted. */
    @Serializable
    @SerialName("ack")
    object Ack : Packet()

    /** Keepalive ping from either side. */
    @Serializable
    @SerialName("ping")
    object Ping : Packet()

    /** Keepalive pong response. */
    @Serializable
    @SerialName("pong")
    object Pong : Packet()

    /** Graceful disconnect notification. */
    @Serializable
    @SerialName("disconnect")
    object Disconnect : Packet()

    /** Attempts to resume a previous session without re-pairing. */
    @Serializable
    @SerialName("session_resume")
    data class SessionResume(val session_token: String) : Packet()

    // ── Phase 3: Data Sync ────────────────────────────────────────────────────

    /** Clipboard content push from either side. */
    @Serializable
    @SerialName("clipboard_sync")
    data class ClipboardSync(val mime: String, val data: String) : Packet()

    /** Android battery status broadcast. */
    @Serializable
    @SerialName("battery_update")
    data class BatteryUpdate(val percent: Int, val charging: Boolean) : Packet()

    /** Android offers a file to the Linux daemon. */
    @Serializable
    @SerialName("file_send_offer")
    data class FileSendOffer(
        val file_id: String,
        val name: String,
        val size: Long,
        val mime: String,
    ) : Packet()

    /** Linux accepts the file offer; Android begins sending chunks. */
    @Serializable
    @SerialName("file_send_accept")
    data class FileSendAccept(val file_id: String) : Packet()

    /** Linux rejects the file offer. */
    @Serializable
    @SerialName("file_send_reject")
    data class FileSendReject(val file_id: String) : Packet()

    /** Signals that all file chunks have been sent; includes integrity hash. */
    @Serializable
    @SerialName("file_send_eof")
    data class FileSendEof(val file_id: String, val sha256: String) : Packet()

    /** Incremental progress update for an in-flight transfer. */
    @Serializable
    @SerialName("file_progress")
    data class FileProgress(
        val file_id: String,
        val bytes_done: Long,
        val total: Long,
    ) : Packet()

    // ── Phase 4: Notifications + Media ───────────────────────────────────────

    /** Forwards an Android notification to the Linux desktop. */
    @Serializable
    @SerialName("notification_post")
    data class NotificationPost(
        val id: Long,
        val app: String,
        val title: String,
        val body: String,
        val icon_b64: String? = null,
    ) : Packet()

    /** Removes a previously forwarded notification. */
    @Serializable
    @SerialName("notification_dismiss")
    data class NotificationDismiss(val id: Long) : Packet()

    /** User replied to a notification from the Linux side. */
    @Serializable
    @SerialName("notification_reply")
    data class NotificationReply(val id: Long, val text: String) : Packet()

    /** Obsidian vault file delta for incremental sync. */
    @Serializable
    @SerialName("obsidian_file_delta")
    data class ObsidianFileDelta(
        val path: String,
        val hash: String,
        val data_b64: String,
    ) : Packet()

    /** Current media playback state from Android media session. */
    @Serializable
    @SerialName("media_state_update")
    data class MediaStateUpdate(
        val title: String,
        val artist: String,
        val album: String,
        val playing: Boolean,
        val position_ms: Long,
        val duration_ms: Long,
    ) : Packet()

    /** Playback control command sent from the Linux desktop. */
    @Serializable
    @SerialName("media_command")
    data class MediaCommand(val action: MediaAction) : Packet()

    // ── Phase 5: Remote Control ───────────────────────────────────────────────

    /** Raw input event (mouse/keyboard) forwarded from Linux to Android. */
    @Serializable
    @SerialName("input_event")
    data class InputEvent(val kind: InputKind, val data: JsonObject) : Packet()

    /** Linux requests execution of a pre-configured command. */
    @Serializable
    @SerialName("run_command_request")
    data class RunCommandRequest(val command_id: String) : Packet()

    /** Output of a completed command execution. */
    @Serializable
    @SerialName("run_command_output")
    data class RunCommandOutput(
        val command_id: String,
        val stdout: String,
        val stderr: String,
        val exit_code: Int,
    ) : Packet()

    /** Begins screen-share streaming from Android to Linux. */
    @Serializable
    @SerialName("screen_share_start")
    object ScreenShareStart : Packet()

    /** Terminates screen-share streaming. */
    @Serializable
    @SerialName("screen_share_stop")
    object ScreenShareStop : Packet()

    // ── Phase 6: Camera-as-Webcam ─────────────────────────────────────────────

    /** Begins streaming Android camera as a virtual webcam on Linux. */
    @Serializable
    @SerialName("webcam_start")
    object WebcamStart : Packet()

    /** Stops the camera stream. */
    @Serializable
    @SerialName("webcam_stop")
    object WebcamStop : Packet()

    // ── WebRTC Signaling (used by media phases 5–6) ───────────────────────────

    /** SDP offer initiating a WebRTC peer connection. */
    @Serializable
    @SerialName("webrtc_sdp_offer")
    data class WebRtcSdpOffer(val session_id: String, val sdp: String) : Packet()

    /** SDP answer completing the WebRTC negotiation. */
    @Serializable
    @SerialName("webrtc_sdp_answer")
    data class WebRtcSdpAnswer(val session_id: String, val sdp: String) : Packet()

    /** ICE candidate for NAT traversal within the local network. */
    @Serializable
    @SerialName("webrtc_ice_candidate")
    data class WebRtcIceCandidate(
        val session_id: String,
        val candidate: String,
        val sdp_mid: String,
        val sdp_m_line_index: Int,
    ) : Packet()

    /** Closes a WebRTC session and frees associated resources. */
    @Serializable
    @SerialName("webrtc_close")
    data class WebRtcClose(val session_id: String) : Packet()
}

/** Media playback actions used in [Packet.MediaCommand]. */
@Serializable
enum class MediaAction {
    @SerialName("play")       Play,
    @SerialName("pause")      Pause,
    @SerialName("next")       Next,
    @SerialName("previous")   Previous,
    @SerialName("seek_to")    SeekTo,
    @SerialName("volume_set") VolumeSet,
}

/** Input event kinds used in [Packet.InputEvent]. */
@Serializable
enum class InputKind {
    @SerialName("mouse_move")   MouseMove,
    @SerialName("mouse_button") MouseButton,
    @SerialName("mouse_scroll") MouseScroll,
    @SerialName("key_press")    KeyPress,
    @SerialName("key_release")  KeyRelease,
}

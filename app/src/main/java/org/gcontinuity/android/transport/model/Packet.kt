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
 * the Rust Packet enum in gcontinuity-daemon/src/transport/packet.rs.
 * All discriminator values are lowercase snake_case to match Rust
 * #[serde(tag = "type", rename_all = "snake_case")].
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed class Packet {

    // ── Handshake ─────────────────────────────────────────────────────────────

    @Serializable
    @SerialName("hello")
    data class Hello(
        val device_id: String,
        val name: String,
        val version: Int,
    ) : Packet()

    @Serializable @SerialName("ack")        object Ack        : Packet()
    @Serializable @SerialName("ping")       object Ping       : Packet()
    @Serializable @SerialName("pong")       object Pong       : Packet()
    @Serializable @SerialName("disconnect") object Disconnect : Packet()

    @Serializable
    @SerialName("session_resume")
    data class SessionResume(val session_token: String) : Packet()

    // ── Phase 3: Data Sync & URL Handoff ──────────────────────────────────────

    @Serializable
    @SerialName("clipboard_sync")
    data class ClipboardSync(val mime: String, val data: String) : Packet()

    /**
     * Phase 3.3 — URL Handoff
     * Instructs the receiving device to open the provided URL in its default browser.
     */
    @Serializable
    @SerialName("open_url")
    data class OpenUrl(val url: String) : Packet()

    /**
     * Android → Linux: Android battery status.
     *
     * Wire: {"type":"battery_info","percent":87,"is_charging":true,"timestamp":1746532800000}
     *
     * Sent by [org.gcontinuity.android.plugins.BatteryPlugin] every 60 s.
     * Received by the Linux daemon → updates D-Bus org.gcontinuity.Battery →
     * Flutter battery_page.dart displays the Android battery level.
     */
    @Serializable
    @SerialName("battery_info")
    data class BatteryInfo(
        val percent: Int,
        @SerialName("is_charging") val isCharging: Boolean,
        val timestamp: Long,
    ) : Packet()

    /**
     * Linux → Android: Linux machine battery status.
     *
     * Wire: {"type":"linux_battery_info","percent":72,"is_charging":false,"timestamp":...}
     *
     * Sent by the Linux daemon's UPower poll every 60 s.
     * Received by [org.gcontinuity.android.plugins.BatteryPlugin.onPacket] →
     * stored in [org.gcontinuity.android.plugins.BatteryPlugin.linuxBatteryState] →
     * displayed in ConnectedScreen TopAppBar below "Connected".
     */
    @Serializable
    @SerialName("linux_battery_info")
    data class LinuxBatteryInfo(
        val percent: Int,
        @SerialName("is_charging") val isCharging: Boolean,
        val timestamp: Long,
    ) : Packet()

    // ── File transfer ─────────────────────────────────────────────────────────

    @Serializable
    @SerialName("file_send_offer")
    data class FileSendOffer(
        val file_id: String,
        val name: String,
        val size: Long,
        val mime: String,
    ) : Packet()

    @Serializable
    @SerialName("file_send_accept")
    data class FileSendAccept(val file_id: String) : Packet()

    @Serializable
    @SerialName("file_send_reject")
    data class FileSendReject(val file_id: String) : Packet()

    @Serializable
    @SerialName("file_send_eof")
    data class FileSendEof(val file_id: String, val sha256: String) : Packet()

    @Serializable
    @SerialName("file_progress")
    data class FileProgress(
        val file_id: String,
        val bytes_done: Long,
        val total: Long,
    ) : Packet()

    // ── Phase 4: Notifications + Media ───────────────────────────────────────

    @Serializable
    @SerialName("notification_post")
    data class NotificationPost(
        val id: Long,
        val app: String,
        val title: String,
        val body: String,
        val icon_b64: String? = null,
    ) : Packet()

    @Serializable
    @SerialName("notification_dismiss")
    data class NotificationDismiss(val id: Long) : Packet()

    @Serializable
    @SerialName("notification_reply")
    data class NotificationReply(val id: Long, val text: String) : Packet()

    @Serializable
    @SerialName("obsidian_file_delta")
    data class ObsidianFileDelta(
        val path: String,
        val hash: String,
        val data_b64: String,
    ) : Packet()

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

    @Serializable
    @SerialName("media_command")
    data class MediaCommand(val action: MediaAction) : Packet()

    // ── Phase 5: Remote Control ───────────────────────────────────────────────

    @Serializable
    @SerialName("input_event")
    data class InputEvent(val kind: InputKind, val data: JsonObject) : Packet()

    @Serializable
    @SerialName("run_command_request")
    data class RunCommandRequest(val command_id: String) : Packet()

    @Serializable
    @SerialName("run_command_output")
    data class RunCommandOutput(
        val command_id: String,
        val stdout: String,
        val stderr: String,
        val exit_code: Int,
    ) : Packet()

    @Serializable @SerialName("screen_share_start") object ScreenShareStart : Packet()
    @Serializable @SerialName("screen_share_stop")  object ScreenShareStop  : Packet()

    // ── Phase 6: Camera-as-Webcam ─────────────────────────────────────────────

    @Serializable @SerialName("webcam_start") object WebcamStart : Packet()
    @Serializable @SerialName("webcam_stop")  object WebcamStop  : Packet()

    // ── WebRTC Signaling ──────────────────────────────────────────────────────

    @Serializable
    @SerialName("webrtc_sdp_offer")
    data class WebRtcSdpOffer(val session_id: String, val sdp: String) : Packet()

    @Serializable
    @SerialName("webrtc_sdp_answer")
    data class WebRtcSdpAnswer(val session_id: String, val sdp: String) : Packet()

    @Serializable
    @SerialName("webrtc_ice_candidate")
    data class WebRtcIceCandidate(
        val session_id: String,
        val candidate: String,
        val sdp_mid: String,
        val sdp_m_line_index: Int,
    ) : Packet()

    @Serializable
    @SerialName("webrtc_close")
    data class WebRtcClose(val session_id: String) : Packet()
}

@Serializable
enum class MediaAction {
    @SerialName("play")       Play,
    @SerialName("pause")      Pause,
    @SerialName("next")       Next,
    @SerialName("previous")   Previous,
    @SerialName("seek_to")    SeekTo,
    @SerialName("volume_set") VolumeSet,
}

@Serializable
enum class InputKind {
    @SerialName("mouse_move")   MouseMove,
    @SerialName("mouse_button") MouseButton,
    @SerialName("mouse_scroll") MouseScroll,
    @SerialName("key_press")    KeyPress,
    @SerialName("key_release")  KeyRelease,
}
package org.gcontinuity.android.transport

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.gcontinuity.android.transport.model.InputKind
import org.gcontinuity.android.transport.model.MediaAction
import org.gcontinuity.android.transport.model.Packet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Roundtrip serialization tests for every [Packet] subclass.
 *
 * Verifies that each variant encodes to JSON with the correct "type" discriminator
 * and decodes back to an equal object — guaranteeing protocol wire compatibility
 * with the Rust gcontinuity-daemon.
 */
class PacketSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private inline fun <reified T : Packet> roundtrip(packet: T): T {
        val encoded = json.encodeToString(Packet.serializer(), packet)
        val decoded = json.decodeFromString(Packet.serializer(), encoded)
        assertEquals(packet, decoded)
        @Suppress("UNCHECKED_CAST")
        return decoded as T
    }

    private fun encode(packet: Packet): String =
        json.encodeToString(Packet.serializer(), packet)

    // ── Handshake ─────────────────────────────────────────────────────────────

    @Test fun `Hello roundtrips and has correct type field`() {
        val p = Packet.Hello(device_id = "dev-1", name = "Pixel 9", version = 1)
        val result = roundtrip(p)
        assertEquals("dev-1", result.device_id)
        assertTrue(encode(p).contains("\"type\":\"hello\""))
    }

    @Test fun `Ack roundtrips`() { roundtrip(Packet.Ack) }

    @Test fun `Ping roundtrips`() { roundtrip(Packet.Ping) }

    @Test fun `Pong roundtrips`() { roundtrip(Packet.Pong) }

    @Test fun `Disconnect roundtrips`() { roundtrip(Packet.Disconnect) }

    @Test fun `SessionResume roundtrips`() {
        roundtrip(Packet.SessionResume(session_token = "tok-abc-123"))
    }

    // ── Phase 3 ───────────────────────────────────────────────────────────────

    @Test fun `ClipboardSync roundtrips`() {
        roundtrip(Packet.ClipboardSync(mime = "text/plain", data = "hello world"))
    }

    @Test fun `BatteryUpdate roundtrips`() {
        val p = roundtrip(Packet.BatteryUpdate(percent = 72, charging = true))
        assertEquals(72, p.percent)
        assertTrue(p.charging)
    }

    @Test fun `FileSendOffer roundtrips`() {
        roundtrip(
            Packet.FileSendOffer(
                file_id = "f1", name = "photo.jpg", size = 1_048_576L, mime = "image/jpeg"
            )
        )
    }

    @Test fun `FileSendAccept roundtrips`() { roundtrip(Packet.FileSendAccept("f1")) }

    @Test fun `FileSendReject roundtrips`() { roundtrip(Packet.FileSendReject("f1")) }

    @Test fun `FileSendEof roundtrips`() {
        roundtrip(Packet.FileSendEof(file_id = "f1", sha256 = "aabbcc"))
    }

    @Test fun `FileProgress roundtrips`() {
        roundtrip(Packet.FileProgress(file_id = "f1", bytes_done = 512L, total = 1024L))
    }

    // ── Phase 4 ───────────────────────────────────────────────────────────────

    @Test fun `NotificationPost roundtrips with null icon`() {
        val p = roundtrip(
            Packet.NotificationPost(
                id = 42L, app = "com.example", title = "Hello", body = "World"
            )
        )
        assertEquals(42L, p.id)
        assertEquals(null, p.icon_b64)
    }

    @Test fun `NotificationPost roundtrips with icon`() {
        roundtrip(
            Packet.NotificationPost(
                id = 1L, app = "app", title = "T", body = "B", icon_b64 = "base64data"
            )
        )
    }

    @Test fun `NotificationDismiss roundtrips`() { roundtrip(Packet.NotificationDismiss(1L)) }

    @Test fun `NotificationReply roundtrips`() {
        roundtrip(Packet.NotificationReply(id = 1L, text = "Reply text"))
    }

    @Test fun `ObsidianFileDelta roundtrips`() {
        roundtrip(
            Packet.ObsidianFileDelta(
                path = "vault/note.md", hash = "sha256abc", data_b64 = "encodeddata"
            )
        )
    }

    @Test fun `MediaStateUpdate roundtrips`() {
        roundtrip(
            Packet.MediaStateUpdate(
                title = "Song", artist = "Artist", album = "Album",
                playing = true, position_ms = 30_000L, duration_ms = 240_000L
            )
        )
    }

    @Test fun `MediaCommand roundtrips for each action`() {
        MediaAction.entries.forEach { action ->
            roundtrip(Packet.MediaCommand(action = action))
        }
    }

    // ── Phase 5 ───────────────────────────────────────────────────────────────

    @Test fun `InputEvent roundtrips`() {
        val data: JsonObject = buildJsonObject { put("x", 100); put("y", 200) }
        roundtrip(Packet.InputEvent(kind = InputKind.MouseMove, data = data))
    }

    @Test fun `RunCommandRequest roundtrips`() {
        roundtrip(Packet.RunCommandRequest(command_id = "cmd-001"))
    }

    @Test fun `RunCommandOutput roundtrips`() {
        roundtrip(
            Packet.RunCommandOutput(
                command_id = "cmd-001", stdout = "ok\n", stderr = "", exit_code = 0
            )
        )
    }

    @Test fun `ScreenShareStart roundtrips`() { roundtrip(Packet.ScreenShareStart) }
    @Test fun `ScreenShareStop roundtrips`() { roundtrip(Packet.ScreenShareStop) }

    // ── Phase 6 ───────────────────────────────────────────────────────────────

    @Test fun `WebcamStart roundtrips`() { roundtrip(Packet.WebcamStart) }
    @Test fun `WebcamStop roundtrips`() { roundtrip(Packet.WebcamStop) }

    // ── WebRTC signaling ──────────────────────────────────────────────────────

    @Test fun `WebRtcSdpOffer roundtrips`() {
        roundtrip(Packet.WebRtcSdpOffer(session_id = "s1", sdp = "v=0\r\n..."))
    }

    @Test fun `WebRtcSdpAnswer roundtrips`() {
        roundtrip(Packet.WebRtcSdpAnswer(session_id = "s1", sdp = "v=0\r\n..."))
    }

    @Test fun `WebRtcIceCandidate roundtrips`() {
        roundtrip(
            Packet.WebRtcIceCandidate(
                session_id = "s1", candidate = "candidate:...",
                sdp_mid = "0", sdp_m_line_index = 0
            )
        )
    }

    @Test fun `WebRtcClose roundtrips`() { roundtrip(Packet.WebRtcClose(session_id = "s1")) }

    // ── Error cases ───────────────────────────────────────────────────────────

    @Test(expected = SerializationException::class)
    fun `unknown type field throws SerializationException`() {
        json.decodeFromString(
            Packet.serializer(),
            """{"type":"totally_unknown_type","foo":"bar"}"""
        )
    }

    @Test(expected = SerializationException::class)
    fun `Hello with missing device_id field throws SerializationException`() {
        json.decodeFromString(
            Packet.serializer(),
            """{"type":"hello","name":"Pixel","version":1}"""
        )
    }

    // ── Enum serialization ────────────────────────────────────────────────────

    @Test fun `MediaAction serializes to lowercase snake_case`() {
        val encoded = encode(Packet.MediaCommand(action = MediaAction.SeekTo))
        assertTrue(encoded.contains("\"seek_to\""))
        assertFalse(encoded.contains("SeekTo"))
    }

    @Test fun `InputKind serializes to lowercase snake_case`() {
        val data: JsonObject = buildJsonObject {}
        val encoded = encode(Packet.InputEvent(kind = InputKind.MouseButton, data = data))
        assertTrue(encoded.contains("\"mouse_button\""))
        assertFalse(encoded.contains("MouseButton"))
    }

    @Test fun `all MediaAction values roundtrip correctly`() {
        MediaAction.entries.forEach { action ->
            val encoded = json.encodeToString(MediaAction.serializer(), action)
            val decoded = json.decodeFromString(MediaAction.serializer(), encoded)
            assertEquals(action, decoded)
        }
    }

    @Test fun `all InputKind values roundtrip correctly`() {
        InputKind.entries.forEach { kind ->
            val encoded = json.encodeToString(InputKind.serializer(), kind)
            val decoded = json.decodeFromString(InputKind.serializer(), encoded)
            assertEquals(kind, decoded)
        }
    }
}

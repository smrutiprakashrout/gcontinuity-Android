package org.gcontinuity.android.transport.webrtc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gcontinuity.android.util.Sha256Util
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeMap

/**
 * Unit tests for [FileTransferSession] protocol logic.
 *
 * SHA-256 verification, chunk framing, and chunk re-assembly are tested as pure
 * JVM unit tests (no Android framework required). [ContentResolver]/[MediaStore]
 * save logic is not covered here — it requires an instrumented Robolectric test.
 */
class FileTransferSessionTest {

    // ── Chunk frame protocol ──────────────────────────────────────────────────

    /**
     * Verifies the binary frame format: first 4 bytes are the chunk index in
     * little-endian byte order, followed by the raw payload bytes.
     */
    @Test
    fun `chunk frame has 4-byte LE index prefix followed by payload`() {
        val payload    = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val chunkIndex = 7

        // Encode (mirrors Sender.sendFile)
        val frame = ByteBuffer.allocate(4 + payload.size)
        frame.order(ByteOrder.LITTLE_ENDIAN).putInt(chunkIndex)
        frame.order(ByteOrder.BIG_ENDIAN).put(payload)
        frame.flip()

        // Decode (mirrors Receiver.processMessage)
        val readIndex   = frame.order(ByteOrder.LITTLE_ENDIAN).getInt()
        frame.order(ByteOrder.BIG_ENDIAN)
        val readPayload = ByteArray(frame.remaining()).also { frame.get(it) }

        assertEquals(chunkIndex, readIndex)
        assertTrue(payload.contentEquals(readPayload))
    }

    /**
     * Verifies that chunks inserted out-of-order into a [TreeMap] keyed by index
     * are reassembled in ascending index order on [reduce].
     */
    @Test
    fun `receiver reassembles out-of-order chunks in correct order`() {
        val chunks = TreeMap<Int, ByteArray>()
        chunks[2] = byteArrayOf(0x05, 0x06)
        chunks[0] = byteArrayOf(0x01, 0x02)
        chunks[1] = byteArrayOf(0x03, 0x04)

        val assembled = chunks.values.reduce { acc, bytes -> acc + bytes }
        val expected  = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06)

        assertTrue(expected.contentEquals(assembled))
    }

    // ── SHA-256 verification ──────────────────────────────────────────────────

    @Test
    fun `SHA-256 of identical data produces identical hash`() {
        val data = "Hello, GContinuity!".toByteArray()
        assertEquals(Sha256Util.ofBytes(data), Sha256Util.ofBytes(data))
    }

    @Test
    fun `SHA-256 verification fails when data is tampered`() {
        val original = "original content".toByteArray()
        val tampered = "tampered content".toByteArray()
        assertFalse(
            "Tampered data must not match original SHA-256",
            Sha256Util.ofBytes(original) == Sha256Util.ofBytes(tampered),
        )
    }

    @Test
    fun `SHA-256 returns lowercase hex string of length 64`() {
        val hash = Sha256Util.ofBytes("test".toByteArray())
        assertEquals(64, hash.length)
        assertTrue("Hash must be lowercase hex", hash.all { it.isDigit() || it in 'a'..'f' })
    }

    // ── Header JSON format ────────────────────────────────────────────────────

    @Test
    fun `header JSON contains file_id name size total_chunks`() {
        val fileId      = "file-001"
        val name        = "photo.jpg"
        val size        = 1_048_576L
        val totalChunks = (size + 65_535) / 65_536

        val header =
            """{"file_id":"$fileId","name":"$name","size":$size,"total_chunks":$totalChunks}"""

        val json   = Json { ignoreUnknownKeys = true }
        val obj    = json.parseToJsonElement(header) as JsonObject

        assertEquals(fileId,      obj["file_id"]!!.jsonPrimitive.content)
        assertEquals(name,        obj["name"]!!.jsonPrimitive.content)
        assertEquals(size,        obj["size"]!!.jsonPrimitive.content.toLong())
        assertEquals(totalChunks, obj["total_chunks"]!!.jsonPrimitive.content.toLong())
    }
}

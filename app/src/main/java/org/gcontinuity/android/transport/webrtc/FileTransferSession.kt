package org.gcontinuity.android.transport.webrtc

import android.content.ContentValues
import android.content.Context
import android.net.Uri

import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gcontinuity.android.transport.TransportManager
import org.gcontinuity.android.transport.model.Packet
import org.gcontinuity.android.util.Sha256Util
import org.webrtc.DataChannel
import org.webrtc.PeerConnection
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeMap

private const val TAG = "FileTransfer"

/**
 * Frame protocol for binary DataChannel file transfer:
 * - **HEADER** (text frame): `{ "file_id": "…", "name": "…", "size": N, "total_chunks": N }`
 * - **CHUNKS** (binary frames): `[4-byte chunk_index LE][payload bytes]`
 * - **EOF** (WebSocket packet, not DataChannel): `Packet.FileSendEof` with SHA-256
 *
 * 64 KB chunks keep buffered-amount manageable on the OkHttp/libdatachannel side.
 */
private const val CHUNK_SIZE = 65_536

/** Base class for send and receive sides of a DataChannel file transfer. */
sealed class FileTransferSession {

    /** Unique file identifier matching [Packet.FileSendOffer.file_id]. */
    abstract val fileId: String

    // ── Sender ────────────────────────────────────────────────────────────────

    /**
     * Sends a file to the Linux daemon via a WebRTC DataChannel.
     *
     * Workflow:
     * 1. Create an ordered, reliable DataChannel on the [peerConnection].
     * 2. Wait for the channel to open.
     * 3. Send the JSON header frame.
     * 4. Stream 64 KB binary chunks with 4-byte LE index prefix.
     * 5. Send [Packet.FileSendEof] via WebSocket for integrity verification.
     */
    class Sender(
        override val fileId: String,
        private val peerConnection: PeerConnection,
        private val transportManager: TransportManager,
        private val context: Context,
    ) : FileTransferSession() {

        private lateinit var dataChannel: DataChannel

        private val _progressBytes = MutableStateFlow(0L)
        /** Bytes sent so far; updated incrementally for UI progress bars. */
        val progressBytes: StateFlow<Long> = _progressBytes.asStateFlow()

        /**
         * Creates the DataChannel and registers a state observer that triggers
         * [sendFile] once the channel reaches the OPEN state.
         */
        fun initiate(uri: Uri) {
            val init = DataChannel.Init().apply {
                ordered  = true
                protocol = "gcontinuity.file"
            }
            dataChannel = peerConnection.createDataChannel("file-$fileId", init)
            dataChannel.registerObserver(object : DataChannel.Observer {
                override fun onStateChange() {
                    if (dataChannel.state() == DataChannel.State.OPEN) {
                        sendFile(uri)
                    }
                }
                override fun onMessage(buffer: DataChannel.Buffer) {}
                override fun onBufferedAmountChange(previous: Long) {}
            })
        }

        private fun sendFile(uri: Uri) {
            val cr          = context.contentResolver
            val name        = resolveFileName(uri, cr)
            val size        = resolveFileSize(uri, cr)
            val totalChunks = (size + CHUNK_SIZE - 1) / CHUNK_SIZE

            // JSON header frame (text)
            val header =
                """{"file_id":"$fileId","name":"$name","size":$size,"total_chunks":$totalChunks}"""
            dataChannel.send(DataChannel.Buffer(ByteBuffer.wrap(header.toByteArray()), false))

            // Binary chunk frames: [4-byte chunk_index LE][payload]
            var chunkIndex = 0
            var bytesSent  = 0L
            cr.openInputStream(uri)?.use { stream ->
                val buf = ByteArray(CHUNK_SIZE)
                while (true) {
                    val bytesRead = stream.read(buf)
                    if (bytesRead == -1) break
                    val frame = ByteBuffer.allocate(4 + bytesRead)
                    frame.order(ByteOrder.LITTLE_ENDIAN).putInt(chunkIndex++)
                    frame.order(ByteOrder.BIG_ENDIAN).put(buf, 0, bytesRead)
                    frame.flip()
                    dataChannel.send(DataChannel.Buffer(frame, true))
                    bytesSent += bytesRead
                    _progressBytes.value = bytesSent
                }
            }

            // EOF via WebSocket (not DataChannel) for integrity check
            val sha256 = Sha256Util.ofUri(uri, cr)
            transportManager.sendPacket(Packet.FileSendEof(file_id = fileId, sha256 = sha256))
            Log.i(TAG, "Sent file '$name' ($bytesSent bytes) sha256=$sha256")
        }

        private fun resolveFileName(uri: Uri, cr: android.content.ContentResolver): String {
            cr.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) return it.getString(0) }
            return uri.lastPathSegment ?: fileId
        }

        private fun resolveFileSize(uri: Uri, cr: android.content.ContentResolver): Long {
            cr.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)
                ?.use { if (it.moveToFirst()) return it.getLong(0) }
            return 0L
        }
    }

    // ── Receiver ──────────────────────────────────────────────────────────────

    /**
     * Receives a file from the Linux daemon via a WebRTC DataChannel.
     *
     * Chunks are buffered in a [TreeMap] (keyed by index) so they can arrive out
     * of order safely, even though the DataChannel is configured as ordered. On
     * [onEof] the chunks are concatenated, SHA-256 verified, then written to the
     * Downloads folder via the MediaStore IS_PENDING pattern (API 29+).
     */
    class Receiver(
        override val fileId: String,
        private val dataChannel: DataChannel,
        private val transportManager: TransportManager,
        private val context: Context,
    ) : FileTransferSession() {

        private val chunks    = TreeMap<Int, ByteArray>()
        private var fileName  : String? = null
        private var totalSize : Long    = 0L
        private var expectedSha256: String? = null

        private val _progressBytes = MutableStateFlow(0L)
        /** Bytes received so far; updated per-chunk for UI progress bars. */
        val progressBytes: StateFlow<Long> = _progressBytes.asStateFlow()

        private val json = Json { ignoreUnknownKeys = true }

        /** Registers a [DataChannel.Observer] to receive frames from Linux. */
        fun attach() {
            dataChannel.registerObserver(object : DataChannel.Observer {
                override fun onMessage(buffer: DataChannel.Buffer) = processMessage(buffer)
                override fun onStateChange() {}
                override fun onBufferedAmountChange(previous: Long) {}
            })
        }

        private fun processMessage(buffer: DataChannel.Buffer) {
            if (buffer.binary) {
                // Binary frame: [4-byte chunk_index LE][payload bytes]
                val bb    = buffer.data.order(ByteOrder.LITTLE_ENDIAN)
                val index = bb.getInt()
                bb.order(ByteOrder.BIG_ENDIAN)
                val data  = ByteArray(bb.remaining()).also { bb.get(it) }
                chunks[index] = data
                _progressBytes.value += data.size.toLong()
            } else {
                // Text frame: JSON header
                val text = Charsets.UTF_8.decode(buffer.data).toString()
                parseHeader(text)
            }
        }

        private fun parseHeader(text: String) {
            try {
                val obj = json.parseToJsonElement(text) as? JsonObject ?: return
                if (obj.containsKey("total_chunks")) {
                    fileName  = obj["name"]?.jsonPrimitive?.content
                    totalSize = obj["size"]?.jsonPrimitive?.content?.toLong() ?: 0L
                    Log.d(TAG, "Receiving: $fileName ($totalSize bytes)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing header frame: $text", e)
            }
        }

        /**
         * Called by [WebRtcManager.onFileSendEof] when the WebSocket EOF packet arrives.
         * Triggers chunk assembly, SHA-256 verification, and MediaStore write.
         *
         * @param sha256 Lowercase hex SHA-256 expected by the sender.
         */
        fun onEof(sha256: String) {
            expectedSha256 = sha256
            assembleAndSave()
        }

        private fun assembleAndSave() {
            if (chunks.isEmpty()) {
                Log.e(TAG, "No chunks received for $fileId — aborting")
                return
            }
            val assembled   = chunks.values.reduce { acc, bytes -> acc + bytes }
            val actualSha256 = Sha256Util.ofBytes(assembled)
            if (actualSha256 != expectedSha256) {
                Log.e(TAG, "SHA-256 mismatch for $fileId! " +
                    "Expected $expectedSha256 got $actualSha256")
                return
            }
            saveToDownloads(fileName ?: fileId, assembled)
        }

        private fun saveToDownloads(name: String, data: ByteArray) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, guessMimeType(name))
                put(MediaStore.Downloads.IS_PENDING, 1)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: run {
                Log.e(TAG, "MediaStore insert failed for $name")
                return
            }
            context.contentResolver.openOutputStream(uri)?.use { it.write(data) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            Log.i(TAG, "Saved '$name' (${data.size} bytes) to Downloads")
        }

        private fun guessMimeType(name: String): String = when {
            name.endsWith(".jpg", ignoreCase = true) ||
            name.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            name.endsWith(".png", ignoreCase = true)  -> "image/png"
            name.endsWith(".pdf", ignoreCase = true)  -> "application/pdf"
            name.endsWith(".mp4", ignoreCase = true)  -> "video/mp4"
            name.endsWith(".mp3", ignoreCase = true)  -> "audio/mpeg"
            name.endsWith(".zip", ignoreCase = true)  -> "application/zip"
            else                                       -> "application/octet-stream"
        }
    }
}

package org.gcontinuity.android.transport.model

/**
 * Tracks the progress of a single in-flight file transfer (send or receive).
 *
 * Immutable snapshot — replace the whole object when state changes rather than
 * mutating fields. Stored as a [Map] of file_id → [FileTransferState] inside
 * [WebRtcManager].
 */
data class FileTransferState(
    /** Unique identifier matching [Packet.FileSendOffer.file_id]. */
    val fileId: String,

    /** Human-readable filename. */
    val fileName: String,

    /** Total expected byte count; 0 if not yet known. */
    val totalBytes: Long,

    /** Bytes successfully transferred so far. */
    val bytesTransferred: Long = 0L,

    /** Whether this device is the sender or receiver. */
    val direction: Direction = Direction.RECEIVING,

    /** Current lifecycle status. */
    val status: Status = Status.PENDING,
) {
    /**
     * Transfer progress as a fraction in [0f, 1f].
     * Returns 0f when [totalBytes] is unknown.
     */
    val progressFraction: Float
        get() = if (totalBytes == 0L) 0f
                else bytesTransferred.toFloat() / totalBytes.toFloat()

    /** Transfer progress as a whole percentage in [0, 100]. */
    val progressPercent: Int get() = (progressFraction * 100).toInt()

    /** Which direction the transfer is moving relative to this device. */
    enum class Direction { SENDING, RECEIVING }

    /** Lifecycle status of the transfer. */
    enum class Status { PENDING, IN_PROGRESS, COMPLETED, FAILED }
}

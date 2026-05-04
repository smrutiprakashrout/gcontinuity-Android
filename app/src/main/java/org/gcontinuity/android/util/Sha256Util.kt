package org.gcontinuity.android.util

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import java.security.MessageDigest

/**
 * Utility functions for computing SHA-256 checksums of files and byte arrays.
 *
 * Used by [FileTransferSession] to verify file integrity after transfer. All
 * functions return lowercase hexadecimal strings (64 characters for SHA-256).
 */
object Sha256Util {

    /**
     * Computes SHA-256 of the content at [uri] using [contentResolver].
     *
     * Streams the content in 64 KB chunks to avoid loading large files into
     * memory. Throws [IllegalStateException] if the URI cannot be opened.
     *
     * @param uri             Content URI (e.g. from MediaStore or file picker).
     * @param contentResolver Application [ContentResolver] from context.
     * @return Lowercase hex SHA-256 string (64 characters).
     */
    fun ofUri(uri: Uri, contentResolver: ContentResolver): String {
        val digest = MessageDigest.getInstance("SHA-256")
        contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(65_536)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        } ?: error("Cannot open URI for SHA-256 computation: $uri")
        return digest.digest().toHex()
    }

    /**
     * Computes SHA-256 of [bytes] in-memory.
     *
     * @param bytes Raw byte content to hash.
     * @return Lowercase hex SHA-256 string (64 characters).
     */
    fun ofBytes(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

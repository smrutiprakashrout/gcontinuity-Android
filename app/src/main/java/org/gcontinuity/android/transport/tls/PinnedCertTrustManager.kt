package org.gcontinuity.android.transport.tls

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * [X509TrustManager] that accepts ONLY the certificate whose SHA-256 DER
 * fingerprint matches [pinnedSha256].
 */
class PinnedCertTrustManager(private val pinnedSha256: ByteArray) : X509TrustManager {

    init {
        require(pinnedSha256.size == 32) {
            "pinnedSha256 must be exactly 32 bytes; got ${pinnedSha256.size}"
        }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("Server certificate chain is empty")
        val digest = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
        if (!digest.contentEquals(pinnedSha256)) {
            throw CertificateException(
                "Certificate SHA-256 mismatch — possible MITM. " +
                        "Expected ${pinnedSha256.toHex()}, got ${digest.toHex()}"
            )
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/**
 * Converts a fingerprint string to a raw 32-byte SHA-256 array.
 *
 * ROOT CAUSE OF CRASH:
 * The daemon sends PairAccept with fingerprint as PLAIN HEX (no colons):
 *   "4c1af889d2b7e5f6126fc139a30b81a8fd6800266fe6b1dad89ac450a0180446"
 *
 * DeviceStore stores it lowercased but still without colons.
 * On reconnect, GContinuityService calls store.getFingerprint() which
 * returns the plain hex string, then calls hexColonToByteArray() on it.
 * The old implementation split on ':' getting ONE 64-char chunk, then
 * called toInt(16) on it — Int overflows on a 64-char hex string → CRASH.
 *
 * FIX: Detect format automatically and handle both:
 *   - Plain hex (64 chars, no colons): parse 2 chars at a time
 *   - Colon-separated (e.g. "4c:1a:f8:..."): split on colon as before
 */
fun String.hexColonToByteArray(): ByteArray {
    val clean = this.trim()
    return if (clean.contains(':')) {
        // Colon-separated format: "4c:1a:f8:89:..."
        clean.split(":").map { it.trim().toInt(16).toByte() }.toByteArray()
    } else {
        // Plain hex format: "4c1af889d2b7e5f6..."
        require(clean.length % 2 == 0) {
            "Plain hex fingerprint must have even length, got ${clean.length}: $clean"
        }
        ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
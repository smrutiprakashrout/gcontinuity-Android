package org.gcontinuity.android.network.ws

import android.util.Log
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

object TlsHelper {
    private const val TAG = "TlsHelper"

    /**
     * Compute SHA-256 fingerprint of the certificate's DER encoding.
     * Always returns lowercase hex separated by colons, e.g. "af:50:06:4d:..."
     * This matches the format produced by openssl / rustls on the Linux side.
     */
    private fun X509Certificate.sha256Fingerprint(): String {
        val sha256 = MessageDigest.getInstance("SHA-256").digest(encoded)
        return sha256.joinToString(":") { "%02x".format(it) }
    }

    // ── Trusted-device mode ────────────────────────────────────────────────

    /**
     * Build an SSLContext that only trusts certificates whose SHA-256
     * fingerprint is in [trustedFingerprints].  Comparison is case-insensitive
     * (both sides are normalised to lowercase).
     */
    fun buildSslContext(trustedFingerprints: Set<String>): Pair<SSLContext, X509TrustManager> {
        // Normalise stored fingerprints to lowercase once, up front.
        val normalised = trustedFingerprints.mapTo(HashSet()) { it.lowercase() }

        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw UnsupportedOperationException("Client auth not supported")
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) throw CertificateException("Empty certificate chain")
                val fp = chain[0].sha256Fingerprint() // already lowercase
                if (fp !in normalised) {
                    throw CertificateException(
                        "Untrusted fingerprint: $fp. Expected one of: $normalised"
                    )
                }
                Log.d(TAG, "Server cert trusted: $fp")
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), SecureRandom())
        return sslContext to trustManager
    }

    // ── First-pairing mode ─────────────────────────────────────────────────

    /**
     * Build an SSLContext for first-time pairing.  Any certificate is accepted
     * (the user will visually verify the fingerprint on both screens), and the
     * fingerprint is recorded so it can be stored after the user confirms.
     */
    fun buildFirstPairingSslContext(): Triple<SSLContext, X509TrustManager, () -> String?> {
        var recordedFingerprint: String? = null

        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw UnsupportedOperationException("Client auth not supported")
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) throw CertificateException("Empty certificate chain")
                // Accept unconditionally — user verifies fingerprint visually.
                recordedFingerprint = chain[0].sha256Fingerprint() // lowercase
                Log.d(TAG, "First-pairing: recorded fingerprint: $recordedFingerprint")
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), SecureRandom())
        return Triple(sslContext, trustManager) { recordedFingerprint }
    }

    // ── OkHttp client builders ─────────────────────────────────────────────

    fun buildOkHttpClient(trustedFingerprints: Set<String>): OkHttpClient {
        val (sslContext, trustManager) = buildSslContext(trustedFingerprints)
        return buildOkHttpClient(sslContext, trustManager)
    }

    fun buildFirstPairingOkHttpClient(): Pair<OkHttpClient, () -> String?> {
        val (sslContext, trustManager, getFp) = buildFirstPairingSslContext()
        return buildOkHttpClient(sslContext, trustManager) to getFp
    }

    private fun buildOkHttpClient(
        sslContext: SSLContext,
        trustManager: X509TrustManager,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true } // self-signed certs on LAN — hostname check is irrelevant
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
}
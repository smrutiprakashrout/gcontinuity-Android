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

    fun buildSslContext(trustedFingerprints: Set<String>): Pair<SSLContext, X509TrustManager> {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw UnsupportedOperationException("Client auth not supported")
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) throw CertificateException("Empty certificate chain")
                val cert = chain[0]
                val der = cert.encoded
                val sha256 = MessageDigest.getInstance("SHA-256").digest(der)
                val fp = sha256.joinToString(":") { "%02X".format(it) }
                if (fp !in trustedFingerprints) {
                    throw CertificateException("Untrusted fingerprint: $fp. Expected one of: $trustedFingerprints")
                }
                Log.d(TAG, "Server cert trusted: $fp")
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), SecureRandom())
        return Pair(sslContext, trustManager)
    }

    fun buildFirstPairingSslContext(): Triple<SSLContext, X509TrustManager, () -> String?> {
        var recordedFingerprint: String? = null

        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw UnsupportedOperationException("Client auth not supported")
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) throw CertificateException("Empty certificate chain")
                val cert = chain[0]
                val der = cert.encoded
                val sha256 = MessageDigest.getInstance("SHA-256").digest(der)
                recordedFingerprint = sha256.joinToString(":") { "%02X".format(it) }
                Log.d(TAG, "First-pairing: recorded fingerprint: $recordedFingerprint")
                // Accept all for first pairing — user will visually verify
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), SecureRandom())
        return Triple(sslContext, trustManager) { recordedFingerprint }
    }

    fun buildOkHttpClient(trustedFingerprints: Set<String>): OkHttpClient {
        val (sslContext, trustManager) = buildSslContext(trustedFingerprints)
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    fun buildFirstPairingOkHttpClient(): Pair<OkHttpClient, () -> String?> {
        val (sslContext, trustManager, getFp) = buildFirstPairingSslContext()
        val client = OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
        return Pair(client, getFp)
    }
}

package org.gcontinuity.android.transport.tls

import org.conscrypt.Conscrypt
import java.security.Security
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Factory that creates [SSLSocketFactory] instances backed by Conscrypt for TLS 1.3
 * support and [PinnedCertTrustManager] for self-signed certificate acceptance.
 *
 * **Design note**: Conscrypt is used explicitly via [SSLContext.getInstance] with the
 * provider instance, not through the global JCE provider registry, so it works
 * regardless of the provider registration order (BouncyCastle and Conscrypt can coexist).
 */
object TlsSocketFactory {

    /**
     * Installs Conscrypt as the highest-priority JCE security provider.
     *
     * Must be called once in [GContinuityApp.onCreate] **after**
     * [IdentityManager.installBouncyCastle]. Conscrypt is inserted at position 1
     * (highest priority), pushing BouncyCastle to position 2. Both remain available —
     * BouncyCastle is used for cert generation; Conscrypt for TLS connections.
     *
     * Safe to call multiple times — no-ops if already installed.
     */
    fun installConscrypt() {
        if (Security.getProvider(Conscrypt.newProvider().name) == null) {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }
    }

    /**
     * Creates an [SSLSocketFactory] that only trusts the certificate identified by
     * [pinnedSha256] (32-byte SHA-256 of the DER-encoded cert).
     *
     * Returns a [Pair] of (factory, trustManager) because OkHttp requires both to
     * be passed separately to [OkHttpClient.Builder.sslSocketFactory].
     *
     * @param pinnedSha256 32-byte SHA-256 fingerprint of the expected server cert.
     * @return Pair<SSLSocketFactory, X509TrustManager> ready for OkHttp.
     */
    fun create(pinnedSha256: ByteArray): Pair<SSLSocketFactory, X509TrustManager> {
        val trustManager = PinnedCertTrustManager(pinnedSha256)
        // Explicitly pass the Conscrypt provider instance so we don't depend on
        // the global JCE provider list ordering.
        val sslContext = SSLContext.getInstance("TLS", Conscrypt.newProvider())
        sslContext.init(null, arrayOf(trustManager), null)
        return sslContext.socketFactory to trustManager
    }
}

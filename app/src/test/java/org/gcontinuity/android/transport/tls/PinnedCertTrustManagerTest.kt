package org.gcontinuity.android.transport.tls

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.util.Date

/**
 * Unit tests for [PinnedCertTrustManager] and [hexColonToByteArray].
 * Uses BouncyCastle to generate real X.509 test certificates.
 */
class PinnedCertTrustManagerTest {

    private val bcProvider = BouncyCastleProvider()

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Generates a fresh self-signed RSA X.509 certificate for testing. */
    private fun generateCert(): X509Certificate {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        val now = Date()
        val later = Date(System.currentTimeMillis() + 365L * 24 * 3600 * 1000)
        val subject = X500Name("CN=Test")
        val builder = JcaX509v3CertificateBuilder(
            subject, BigInteger.ONE, now, later, subject, kp.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(bcProvider)
            .build(kp.private)
        return JcaX509CertificateConverter()
            .setProvider(bcProvider)
            .getCertificate(builder.build(signer))
    }

    private fun sha256Of(cert: X509Certificate): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)

    // ── Constructor validation ────────────────────────────────────────────────

    @Test
    fun `constructor rejects pin shorter than 32 bytes`() {
        assertThrows(IllegalArgumentException::class.java) {
            PinnedCertTrustManager(ByteArray(31))
        }
    }

    @Test
    fun `constructor rejects pin longer than 32 bytes`() {
        assertThrows(IllegalArgumentException::class.java) {
            PinnedCertTrustManager(ByteArray(33))
        }
    }

    @Test
    fun `constructor accepts exactly 32 bytes`() {
        PinnedCertTrustManager(ByteArray(32)) // no exception expected
    }

    // ── checkServerTrusted ────────────────────────────────────────────────────

    @Test
    fun `correct fingerprint passes without exception`() {
        val cert = generateCert()
        val pin = sha256Of(cert)
        val tm = PinnedCertTrustManager(pin)
        tm.checkServerTrusted(arrayOf(cert), "RSA") // must not throw
    }

    @Test
    fun `wrong fingerprint throws CertificateException`() {
        val cert = generateCert()
        val wrongPin = sha256Of(generateCert()) // different cert's fingerprint
        val tm = PinnedCertTrustManager(wrongPin)
        assertThrows(CertificateException::class.java) {
            tm.checkServerTrusted(arrayOf(cert), "RSA")
        }
    }

    @Test
    fun `empty chain throws CertificateException`() {
        val tm = PinnedCertTrustManager(ByteArray(32))
        assertThrows(CertificateException::class.java) {
            tm.checkServerTrusted(emptyArray(), "RSA")
        }
    }

    @Test
    fun `getAcceptedIssuers returns empty array`() {
        val tm = PinnedCertTrustManager(ByteArray(32))
        assertArrayEquals(emptyArray<X509Certificate>(), tm.getAcceptedIssuers())
    }

    // ── hexColonToByteArray ───────────────────────────────────────────────────

    @Test
    fun `hexColonToByteArray converts uppercase colon hex correctly`() {
        val hex = "AF:50:06:4D"
        val expected = byteArrayOf(0xAF.toByte(), 0x50, 0x06, 0x4D)
        assertArrayEquals(expected, hex.hexColonToByteArray())
    }

    @Test
    fun `hexColonToByteArray converts lowercase colon hex correctly`() {
        val hex = "af:50:06:4d"
        val expected = byteArrayOf(0xAF.toByte(), 0x50, 0x06, 0x4D)
        assertArrayEquals(expected, hex.hexColonToByteArray())
    }

    @Test
    fun `fingerprint roundtrip via hexColonToByteArray`() {
        val cert = generateCert()
        val pin = sha256Of(cert)
        // Reproduce how DeviceStore stores fingerprints
        val hexColon = pin.joinToString(":") { "%02X".format(it) }
        val converted = hexColon.hexColonToByteArray()
        val tm = PinnedCertTrustManager(converted)
        tm.checkServerTrusted(arrayOf(cert), "RSA") // must not throw
    }
}

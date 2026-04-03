package org.gcontinuity.android.identity

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Security
import java.util.Date
import java.util.UUID

class IdentityManager(private val context: Context) {

    companion object {
        const val PREFS_NAME = "gcontinuity_identity"
        private const val TAG = "IdentityManager"

        // Must be called before any BC operations.
        // Removes Android's stripped-down BC provider and inserts the full one.
        fun installBouncyCastle() {
            // Android ships a limited BC provider under the name "BC".
            // We must remove it and insert the full standalone bcprov jar.
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }

        init {
            installBouncyCastle()
        }
    }

    private val bcProvider = BouncyCastleProvider()

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun generateIdentity(deviceName: String): DeviceIdentity {
        Log.i(TAG, "Generating new device identity for: $deviceName")

        // Use Android's built-in KeyPairGenerator (not BC's) to generate the RSA key pair.
        // This avoids any provider conflict for the key generation step.
        val keyPairGen = KeyPairGenerator.getInstance("RSA")
        keyPairGen.initialize(2048)
        val keyPair = keyPairGen.generateKeyPair()

        val now = Date()
        val notAfter = Date(System.currentTimeMillis() + 10L * 365 * 24 * 3600 * 1000)
        val subject = X500Name("CN=$deviceName")
        val serial = BigInteger.valueOf(System.currentTimeMillis())

        // Build the X.509 cert using BouncyCastle's cert builder
        val certBuilder = JcaX509v3CertificateBuilder(
            subject,
            serial,
            now,
            notAfter,
            subject,
            keyPair.public
        )

        // Use "SHA256withRSA" (not SHA256WithRSAEncryption) and pass the BC provider instance
        // directly — this bypasses the broken Android-system BC registration.
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(bcProvider)
            .build(keyPair.private)

        val cert = JcaX509CertificateConverter()
            .setProvider(bcProvider)
            .getCertificate(certBuilder.build(signer))

        // Compute SHA-256 fingerprint of the DER-encoded cert
        val der = cert.encoded
        val sha256 = MessageDigest.getInstance("SHA-256").digest(der)
        val fingerprint = sha256.joinToString(":") { "%02X".format(it) }

        // PEM-encode certificate and private key
        val certPem = pemEncode("CERTIFICATE", cert.encoded)
        val keyPem = pemEncode("PRIVATE KEY", keyPair.private.encoded)

        Log.i(TAG, "Identity generated. Fingerprint: $fingerprint")

        return DeviceIdentity(
            deviceId = UUID.randomUUID().toString(),
            deviceName = deviceName,
            fingerprint = fingerprint,
            certPem = certPem,
            privateKeyPem = keyPem
        )
    }

    private fun pemEncode(type: String, data: ByteArray): String {
        val sw = StringWriter()
        PemWriter(sw).use { pw ->
            pw.writeObject(PemObject(type, data))
        }
        return sw.toString()
    }

    fun loadOrCreate(deviceName: String): DeviceIdentity {
        val existingId = prefs.getString("device_id", null)
        if (existingId != null) {
            Log.d(TAG, "Loaded existing identity: $existingId")
            return DeviceIdentity(
                deviceId = existingId,
                deviceName = prefs.getString("device_name", deviceName)!!,
                fingerprint = prefs.getString("fingerprint", "")!!,
                certPem = prefs.getString("cert_pem", "")!!,
                privateKeyPem = prefs.getString("key_pem", "")!!
            )
        }

        val identity = generateIdentity(deviceName)
        prefs.edit().apply {
            putString("device_id", identity.deviceId)
            putString("device_name", identity.deviceName)
            putString("fingerprint", identity.fingerprint)
            putString("cert_pem", identity.certPem)
            putString("key_pem", identity.privateKeyPem)
            apply()
        }
        return identity
    }

    fun getIdentity(): DeviceIdentity? {
        val id = prefs.getString("device_id", null) ?: return null
        return DeviceIdentity(
            deviceId = id,
            deviceName = prefs.getString("device_name", "")!!,
            fingerprint = prefs.getString("fingerprint", "")!!,
            certPem = prefs.getString("cert_pem", "")!!,
            privateKeyPem = prefs.getString("key_pem", "")!!
        )
    }
}

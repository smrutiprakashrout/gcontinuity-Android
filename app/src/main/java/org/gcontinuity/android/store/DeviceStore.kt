package org.gcontinuity.android.store

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gcontinuity.android.network.DeviceInfo

class DeviceStore(private val context: Context) {

    companion object {
        const val PREFS_NAME = "gcontinuity_peers"
        private const val TAG = "DeviceStore"
        private const val PEER_PREFIX = "peer_"
    }

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

    private val json = Json { ignoreUnknownKeys = true }

    fun isTrusted(deviceId: String): Boolean = prefs.contains("$PEER_PREFIX$deviceId")

    fun getFingerprint(deviceId: String): String? {
        val raw = prefs.getString("$PEER_PREFIX$deviceId", null) ?: return null
        return try {
            json.decodeFromString<DeviceInfo>(raw).fingerprint
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse device info for $deviceId", e)
            null
        }
    }

    fun storeTrustedDevice(device: DeviceInfo) {
        val value = json.encodeToString(device)
        prefs.edit().putString("$PEER_PREFIX${device.deviceId}", value).apply()
        Log.i(TAG, "Stored trusted device: ${device.name} (${device.deviceId})")
    }

    fun removeTrustedDevice(deviceId: String) {
        prefs.edit().remove("$PEER_PREFIX$deviceId").apply()
        Log.i(TAG, "Removed trusted device: $deviceId")
    }

    fun listTrustedDevices(): List<DeviceInfo> {
        return prefs.all
            .filterKeys { it.startsWith(PEER_PREFIX) }
            .mapNotNull { (key, value) ->
                try {
                    json.decodeFromString<DeviceInfo>(value as String)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse device at key $key", e)
                    null
                }
            }
    }

    fun clear() {
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(PEER_PREFIX) }
            .forEach { editor.remove(it) }
        editor.apply()
        Log.i(TAG, "Cleared all trusted devices")
    }
}

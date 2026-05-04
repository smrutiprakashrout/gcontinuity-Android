package org.gcontinuity.android.network

import kotlinx.serialization.Serializable

/**
 * Represents a discovered or paired gcontinuity-daemon device.
 *
 * FIX: Added @Serializable — DeviceStore uses kotlinx.serialization to
 * persist DeviceInfo to EncryptedSharedPreferences. Without this annotation
 * the app crashes with SerializationException on acceptPairing().
 */
@Serializable
data class DeviceInfo(
    val deviceId: String,
    val name: String,
    val fingerprint: String,
    val host: String = "",
    val port: Int = 0,
)
package org.gcontinuity.android.identity

data class DeviceIdentity(
    val deviceId: String,
    val deviceName: String,
    val fingerprint: String,
    val certPem: String,
    val privateKeyPem: String
)

package org.gcontinuity.android.util

import org.gcontinuity.android.network.DeviceInfo

fun String.toDeviceIdFromServiceName(): String = removePrefix("gcontinuity-android-")

fun DeviceInfo.displayAddress(): String = if (host.isNotEmpty()) "$host:$port" else "Unknown"

fun ByteArray.toHexFingerprint(): String =
    joinToString(":") { "%02X".format(it) }

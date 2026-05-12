package org.gcontinuity.android.plugins

/**
 * Snapshot of a device's battery status.
 *
 * Reused for both directions:
 * - [BatteryPlugin.batteryState]      — Android's own battery (sent TO Linux daemon)
 * - [BatteryPlugin.linuxBatteryState] — Linux machine's battery (received FROM Linux daemon)
 *
 * @param percent    Battery level 0–100.
 * @param isCharging True if the device is charging or fully charged.
 * @param timestamp  Epoch milliseconds of the last update.
 */
data class BatteryState(
    val percent: Int,
    val isCharging: Boolean,
    val timestamp: Long,
)
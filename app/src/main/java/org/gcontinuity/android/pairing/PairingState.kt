package org.gcontinuity.android.pairing

import org.gcontinuity.android.network.DeviceInfo

sealed class PairingState {
    object Idle : PairingState()
    object Scanning : PairingState()
    data class Discovered(val device: DeviceInfo) : PairingState()
    data class Connecting(val device: DeviceInfo) : PairingState()
    data class AwaitingPair(
        val device: DeviceInfo,
        val linuxFingerprint: String
    ) : PairingState()
    object Reconnecting : PairingState()
    data class PairedConnected(val device: DeviceInfo) : PairingState()
    data class Error(val message: String) : PairingState()
}

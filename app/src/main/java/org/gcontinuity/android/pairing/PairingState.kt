package org.gcontinuity.android.pairing

import org.gcontinuity.android.network.DeviceInfo

sealed class PairingState {

    /** No active connection, nothing happening. */
    object Idle : PairingState()

    /** mDNS scanning, no device found yet. */
    object Scanning : PairingState()

    /** TCP/WebSocket connect attempt in progress. */
    data class Connecting(val device: DeviceInfo) : PairingState()

    /**
     * Connected and waiting for the user to accept/reject pairing.
     * [linuxFingerprint] is the fingerprint advertised by the Linux daemon
     * in its Hello packet — shown to the user for visual verification.
     */
    data class AwaitingPair(
        val device: DeviceInfo,
        val linuxFingerprint: String,
    ) : PairingState()

    /** Fully paired and connected. */
    data class PairedConnected(val device: DeviceInfo) : PairingState()

    /**
     * Connection was lost; the service is trying to reconnect.
     * Carries [device] so the UI can keep showing the device name and the
     * reconnecting banner rather than a blank white page.
     */
    data class Reconnecting(val device: DeviceInfo) : PairingState()

    /** A terminal error the user needs to act on. */
    data class Error(val message: String) : PairingState()
}

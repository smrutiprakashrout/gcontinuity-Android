// org/gcontinuity/android/pairing/PairingManager.kt
//
// CHANGE vs previous version:
//   - Added `is Packet.FeaturePacket` branch to the exhaustive `when` in
//     handlePacket(). FeaturePacket is always intercepted by GContinuityService
//     before reaching PairingManager, so this branch is never executed at
//     runtime — it exists only to satisfy Kotlin's exhaustive when requirement.

package org.gcontinuity.android.pairing

import android.util.Log
import org.gcontinuity.android.identity.DeviceIdentity
import org.gcontinuity.android.network.DeviceInfo
import org.gcontinuity.android.network.Packet
import org.gcontinuity.android.network.toJson
import org.gcontinuity.android.network.ws.WsClient
import org.gcontinuity.android.store.DeviceStore

/**
 * Manages the Phase 1 pairing state machine on the Android side.
 *
 * State transitions:
 * ```
 * Idle
 *  └─ onOpen  → WsClient sends Hello + PairRequest
 *      └─ recv PairAccept → PairedConnected (known device: auto-trust)
 *      └─ recv PairAccept → AwaitingPair → user taps Accept → PairedConnected
 *      └─ recv PairReject → Error
 * ```
 *
 * Note: [Packet.FeaturePacket] (Phase 3–6 feature packets) are intercepted by
 * [org.gcontinuity.android.service.GContinuityService] in its `onPacketReceived`
 * callback BEFORE this class is called. The `is Packet.FeaturePacket` branch
 * below is therefore never reached at runtime — it exists only to satisfy
 * Kotlin's exhaustive `when` requirement on the sealed class.
 */
class PairingManager(
    private val identity: DeviceIdentity,
    private val store: DeviceStore,
    private val wsClient: WsClient,
    private val onStateChange: (PairingState) -> Unit,
) {
    private val TAG = "PairingManager"
    private var currentState: PairingState = PairingState.Idle
    private var linuxDevice: DeviceInfo? = null

    fun getCurrentState(): PairingState = currentState

    private fun setState(state: PairingState) {
        currentState = state
        onStateChange(state)
    }

    fun handlePacket(packet: Packet) {
        when (packet) {

            // ── Linux's Hello reply ──────────────────────────────────────────
            is Packet.Hello -> {
                Log.i(TAG, "Linux Hello received from ${packet.name} (${packet.device_id})")
                linuxDevice = DeviceInfo(
                    deviceId    = packet.device_id,
                    name        = packet.name,
                    fingerprint = "",  // set from PairAccept
                )
            }

            // ── Linux accepts pairing ────────────────────────────────────────
            is Packet.PairAccept -> {
                val device = linuxDevice ?: run {
                    Log.e(TAG, "PairAccept received but no linuxDevice recorded")
                    return
                }
                val updatedDevice = device.copy(fingerprint = packet.fingerprint)
                val stored = store.getFingerprint(device.deviceId)
                when {
                    stored == null -> {
                        Log.i(TAG, "New device ${device.name} — showing pairing UI")
                        setState(
                            PairingState.AwaitingPair(
                                device           = updatedDevice,
                                linuxFingerprint = packet.fingerprint,
                            )
                        )
                    }
                    stored == packet.fingerprint -> {
                        Log.i(TAG, "Auto-trusted: ${device.name}")
                        store.storeTrustedDevice(updatedDevice)
                        setState(PairingState.PairedConnected(updatedDevice))
                    }
                    else -> {
                        Log.e(TAG, "FINGERPRINT MISMATCH for ${device.name} — possible MITM!")
                        wsClient.send(Packet.PairReject("fingerprint_changed"))
                        wsClient.disconnect("fingerprint_changed")
                        setState(PairingState.Error("Security warning: device fingerprint changed"))
                    }
                }
            }

            // ── Linux rejects pairing ────────────────────────────────────────
            is Packet.PairReject -> {
                Log.w(TAG, "Pairing rejected by Linux: ${packet.reason}")
                setState(PairingState.Error("Pairing rejected: ${packet.reason}"))
                wsClient.disconnect(packet.reason)
            }

            // ── PairRequest from Linux (unexpected in client role) ────────────
            is Packet.PairRequest -> {
                Log.d(TAG, "PairRequest from Linux — ignored (unexpected in client role)")
            }

            // ── Keepalive ─────────────────────────────────────────────────────
            is Packet.Ping -> {
                wsClient.send(Packet.Pong)
                Log.d(TAG, "Ping → Pong")
            }

            is Packet.Pong -> {
                Log.d(TAG, "Pong received")
            }

            // ── Graceful disconnect ───────────────────────────────────────────
            is Packet.Disconnect -> {
                Log.i(TAG, "Disconnect received from Linux")
                setState(PairingState.Idle)
            }

            // ── Phase 3–6 feature packets ─────────────────────────────────────
            // Never reached at runtime — GContinuityService intercepts
            // FeaturePacket before calling handlePacket(). Branch required
            // only to satisfy Kotlin's exhaustive when on the sealed class.
            is Packet.FeaturePacket -> {
                Log.w(TAG, "FeaturePacket reached PairingManager unexpectedly — ignored (type=${packet.type})")
            }
        }
    }

    // ── UI-triggered actions ──────────────────────────────────────────────────

    fun acceptPairing(device: DeviceInfo) {
        store.storeTrustedDevice(device)
        setState(PairingState.PairedConnected(device))
        Log.i(TAG, "Pairing accepted locally for ${device.name}")
    }

    fun rejectPairing() {
        wsClient.send(Packet.PairReject("user_rejected"))
        wsClient.disconnect("user_rejected")
        setState(PairingState.Idle)
        Log.i(TAG, "Pairing rejected by user")
    }
}
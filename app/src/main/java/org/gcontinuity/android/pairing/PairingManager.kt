// org/gcontinuity/android/pairing/PairingManager.kt
//
// CHANGES FROM OLD VERSION:
//   1. `Packet.Hello` match no longer reads `fingerprint` — field removed.
//      On receiving Linux's Hello, Android now waits for PairAccept/PairReject
//      (which it receives in response to the PairRequest sent by WsClient).
//   2. `Packet.Ping { timestamp_ms }` → `Packet.Ping` (bare object).
//      `wsClient.send(Packet.Pong(packet.timestamp_ms))` →
//      `wsClient.send(Packet.Pong)` (bare object).
//   3. `Packet.Pong` match no longer destructures timestamp_ms.
//   4. `Packet.Disconnect { reason }` → `Packet.Disconnect` (bare object).
//   5. `Packet.PairReject` still has `reason` field — unchanged.
//   6. `Packet.PairRequest` received from Linux is logged and ignored on the
//      Android side (Linux never sends PairRequest to Android in Phase 1).

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
 */
class PairingManager(
    private val identity: DeviceIdentity,
    private val store: DeviceStore,
    private val wsClient: WsClient,
    private val onStateChange: (PairingState) -> Unit,
) {
    private val TAG = "PairingManager"
    private var currentState: PairingState = PairingState.Idle
    /** The DeviceInfo received from Linux's Hello — populated in onHello(). */
    private var linuxDevice: DeviceInfo? = null

    fun getCurrentState(): PairingState = currentState

    private fun setState(state: PairingState) {
        currentState = state
        onStateChange(state)
    }

    fun handlePacket(packet: Packet) {
        when (packet) {
            // ── Linux's Hello reply ──────────────────────────────────────────
            // Hello no longer carries fingerprint.
            // Android just records Linux's identity; fingerprint comes later
            // in PairAccept.
            is Packet.Hello -> {
                Log.i(TAG, "Linux Hello received from ${packet.name} (${packet.device_id})")
                linuxDevice = DeviceInfo(
                    deviceId    = packet.device_id,
                    name        = packet.name,
                    fingerprint = "",  // will be set from PairAccept
                )
                // WsClient already sent PairRequest in onOpen — nothing more to do here.
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
                        // First time — show pairing UI for user to verify fingerprint.
                        Log.i(TAG, "New device ${device.name} — showing pairing UI")
                        setState(
                            PairingState.AwaitingPair(
                                device           = updatedDevice,
                                linuxFingerprint = packet.fingerprint,
                            )
                        )
                    }
                    stored == packet.fingerprint -> {
                        // Known device and fingerprint matches — auto-trust.
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

            // ── Linux sends a PairRequest (should not happen in normal flow) ─
            is Packet.PairRequest -> {
                Log.d(TAG, "PairRequest received from Linux (unexpected in client role) — ignored")
            }

            // ── Keepalive — Ping is now bare (no timestamp_ms) ────────────────
            is Packet.Ping -> {
                wsClient.send(Packet.Pong)
                Log.d(TAG, "Ping → Pong")
            }

            // ── Pong is bare — no fields to destructure ──────────────────────
            is Packet.Pong -> {
                Log.d(TAG, "Pong received")
            }

            // ── Disconnect is bare — no reason field ─────────────────────────
            is Packet.Disconnect -> {
                Log.i(TAG, "Disconnect received from Linux")
                setState(PairingState.Idle)
            }
        }
    }

    // ── UI-triggered actions ──────────────────────────────────────────────────

    /**
     * Called when the Android user taps "Trust" in the pairing dialog.
     * Stores the device and notifies the feature layer.
     */
    fun acceptPairing(device: DeviceInfo) {
        store.storeTrustedDevice(device)
        // Android does NOT send PairAccept back — Linux already sent PairAccept
        // to Android, so the handshake is complete.  Just update local state.
        setState(PairingState.PairedConnected(device))
        Log.i(TAG, "Pairing accepted locally for ${device.name}")
    }

    /**
     * Called when the Android user taps "Reject" in the pairing dialog.
     */
    fun rejectPairing() {
        wsClient.send(Packet.PairReject("user_rejected"))
        wsClient.disconnect("user_rejected")
        setState(PairingState.Idle)
        Log.i(TAG, "Pairing rejected by user")
    }
}

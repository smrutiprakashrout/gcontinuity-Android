package org.gcontinuity.android.pairing

import android.util.Log
import org.gcontinuity.android.identity.DeviceIdentity
import org.gcontinuity.android.network.DeviceInfo
import org.gcontinuity.android.network.Packet
import org.gcontinuity.android.network.toJson
import org.gcontinuity.android.network.ws.WsClient
import org.gcontinuity.android.store.DeviceStore

class PairingManager(
    private val identity: DeviceIdentity,
    private val store: DeviceStore,
    private val wsClient: WsClient,
    private val onStateChange: (PairingState) -> Unit,
) {
    private val TAG = "PairingManager"
    private var currentState: PairingState = PairingState.Idle

    fun getCurrentState(): PairingState = currentState

    private fun setState(state: PairingState) {
        currentState = state
        onStateChange(state)
    }

    fun handlePacket(packet: Packet) {
        when (packet) {
            is Packet.Hello -> {
                val stored = store.getFingerprint(packet.device_id)
                when {
                    stored == null -> {
                        Log.i(TAG, "Unknown device ${packet.name} — showing pairing UI")
                        val device = DeviceInfo(packet.device_id, packet.name, packet.fingerprint)
                        setState(PairingState.AwaitingPair(device, linuxFingerprint = packet.fingerprint))
                    }
                    stored == packet.fingerprint -> {
                        Log.i(TAG, "Auto-trusted: ${packet.name}")
                        val device = DeviceInfo(packet.device_id, packet.name, packet.fingerprint)
                        setState(PairingState.PairedConnected(device))
                    }
                    else -> {
                        Log.e(TAG, "FINGERPRINT MISMATCH for ${packet.name} — possible MITM!")
                        wsClient.send(Packet.PairReject("fingerprint_changed"))
                        wsClient.disconnect("fingerprint_changed")
                        setState(PairingState.Error("Security warning: device fingerprint changed"))
                    }
                }
            }

            is Packet.PairAccept -> {
                val state = currentState
                if (state is PairingState.AwaitingPair) {
                    store.storeTrustedDevice(
                        DeviceInfo(
                            deviceId = state.device.deviceId,
                            name = state.device.name,
                            fingerprint = packet.fingerprint
                        )
                    )
                    setState(PairingState.PairedConnected(state.device))
                    Log.i(TAG, "Pairing accepted by Linux for ${state.device.name}")
                }
            }

            is Packet.PairReject -> {
                Log.w(TAG, "Pairing rejected: ${packet.reason}")
                setState(PairingState.Error("Pairing rejected: ${packet.reason}"))
                wsClient.disconnect(packet.reason)
            }

            is Packet.Ping -> {
                wsClient.send(Packet.Pong(packet.timestamp_ms))
                Log.d(TAG, "Ping → Pong (${packet.timestamp_ms})")
            }

            is Packet.Pong -> {
                Log.d(TAG, "Pong received")
            }

            is Packet.Disconnect -> {
                Log.i(TAG, "Disconnect from remote: ${packet.reason}")
                setState(PairingState.Idle)
            }

            is Packet.PairRequest -> {
                Log.d(TAG, "PairRequest received (handled by daemon side)")
            }
        }
    }

    fun acceptPairing(device: DeviceInfo) {
        store.storeTrustedDevice(device)
        wsClient.send(Packet.PairAccept(identity.fingerprint))
        setState(PairingState.PairedConnected(device))
        Log.i(TAG, "Pairing accepted for ${device.name}")
    }

    fun rejectPairing() {
        wsClient.send(Packet.PairReject("user_rejected"))
        wsClient.disconnect("user_rejected")
        setState(PairingState.Idle)
        Log.i(TAG, "Pairing rejected by user")
    }
}

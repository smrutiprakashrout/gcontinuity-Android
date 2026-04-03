package org.gcontinuity.android.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.gcontinuity.android.network.DeviceInfo
import org.gcontinuity.android.pairing.PairingState
import org.gcontinuity.android.service.GContinuityService

class MainViewModel : ViewModel() {

    val pairingState: StateFlow<PairingState>
        get() = GContinuityService.instance?.pairingState
            ?: MutableStateFlow(PairingState.Idle)

    val discoveredDevices: StateFlow<List<DeviceInfo>>
        get() = GContinuityService.instance?.discoveredDevices
            ?: MutableStateFlow(emptyList())

    fun acceptPairing() {
        val state = pairingState.value
        if (state is PairingState.AwaitingPair) {
            GContinuityService.instance?.pairingManager?.acceptPairing(state.device)
        }
    }

    fun rejectPairing() {
        GContinuityService.instance?.pairingManager?.rejectPairing()
    }

    fun disconnect() {
        GContinuityService.instance?.wsClient?.disconnect("user_requested")
    }

    fun connectToDevice(device: DeviceInfo) {
        viewModelScope.launch {
            GContinuityService.instance?.wsClient?.connect(device.host, device.port)
        }
    }
}

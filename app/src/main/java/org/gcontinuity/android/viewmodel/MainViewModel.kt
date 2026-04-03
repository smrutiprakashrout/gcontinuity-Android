package org.gcontinuity.android.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.gcontinuity.android.network.DeviceInfo
import org.gcontinuity.android.pairing.PairingState
import org.gcontinuity.android.service.GContinuityService

class MainViewModel : ViewModel() {

    private val _pairingState = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairingState: StateFlow<PairingState> = _pairingState

    private val _discoveredDevices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val discoveredDevices: StateFlow<List<DeviceInfo>> = _discoveredDevices

    val trustedDevices: StateFlow<List<DeviceInfo>>
        get() = GContinuityService.instance?.let { service ->
            MutableStateFlow(service.store.listTrustedDevices())
        } ?: MutableStateFlow(emptyList())

    init {
        viewModelScope.launch {
            while (true) {
                val service = GContinuityService.instance
                if (service != null) {
                    launch {
                        service.pairingState.collect { state ->
                            _pairingState.value = state
                        }
                    }
                    launch {
                        service.discoveredDevices.collect { devices ->
                            _discoveredDevices.value = devices
                        }
                    }
                    break
                }
                delay(200)
            }
        }
    }

    fun unpairDevice(device: DeviceInfo) {
        GContinuityService.instance?.store?.removeTrustedDevice(device.deviceId)
    }

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

    fun refresh() {
        GContinuityService.instance?.triggerRefresh()
    }

    fun connectToDevice(device: DeviceInfo) {
        val service = GContinuityService.instance ?: return
        _pairingState.value = PairingState.Connecting(device)
        viewModelScope.launch(Dispatchers.IO) {
            service.wsClient.connect(device.host, device.port)
        }
    }
}

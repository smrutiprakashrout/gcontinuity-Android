package org.gcontinuity.android.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.gcontinuity.android.network.DeviceInfo
import org.gcontinuity.android.network.Packet
import org.gcontinuity.android.pairing.PairingState
import org.gcontinuity.android.service.GContinuityService
import org.gcontinuity.android.store.ALL_PLUGINS
import org.gcontinuity.android.store.PluginStore

// Changed from ViewModel → AndroidViewModel so PluginStore can get a Context
// without needing a separate factory. Everything else is identical to before.
class MainViewModel(application: Application) : AndroidViewModel(application) {

    // ── Connection state ──────────────────────────────────────────────────

    private val _pairingState = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val discoveredDevices: StateFlow<List<DeviceInfo>> = _discoveredDevices.asStateFlow()

    val trustedDevices: StateFlow<List<DeviceInfo>>
        get() = GContinuityService.instance?.let { service ->
            MutableStateFlow(service.store.listTrustedDevices())
        } ?: MutableStateFlow(emptyList())

    // ── Plugin state ──────────────────────────────────────────────────────

    private val pluginStore = PluginStore(application)

    private val _pluginStates = MutableStateFlow(pluginStore.getAll())
    val pluginStates: StateFlow<Map<String, Boolean>> = _pluginStates.asStateFlow()

    fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        pluginStore.setEnabled(pluginId, enabled)
        _pluginStates.value = pluginStore.getAll()
        // TODO: when Linux daemon supports plugin toggle packets, send here
    }

    // ── Init ──────────────────────────────────────────────────────────────

    init {
        viewModelScope.launch {
            while (true) {
                val service = GContinuityService.instance
                if (service != null) {
                    launch {
                        service.pairingState.collect { _pairingState.value = it }
                    }
                    launch {
                        service.discoveredDevices.collect { _discoveredDevices.value = it }
                    }
                    break
                }
                delay(200)
            }
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────

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

    fun sendPing() {
        GContinuityService.instance?.wsClient?.send(
            Packet.Ping(System.currentTimeMillis())
        )
    }

    fun ringDevice() {
        // TODO: send Ring packet when protocol supports it
    }
}

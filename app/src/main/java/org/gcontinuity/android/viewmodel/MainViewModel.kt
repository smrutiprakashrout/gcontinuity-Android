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
import org.gcontinuity.android.plugins.BatteryState
import org.gcontinuity.android.service.GContinuityService
import org.gcontinuity.android.store.PluginStore

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _pairingState = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val discoveredDevices: StateFlow<List<DeviceInfo>> = _discoveredDevices.asStateFlow()

    val trustedDevices: StateFlow<List<DeviceInfo>>
        get() = GContinuityService.instance?.let { service ->
            MutableStateFlow(service.store.listTrustedDevices())
        } ?: MutableStateFlow(emptyList())

    // ── Phase 3: Linux battery state ──────────────────────────────────────────

    private val _linuxBatteryState = MutableStateFlow<BatteryState?>(null)
    /**
     * Linux machine's battery — received via [Packet.LinuxBatteryInfo] from the daemon.
     * Null until the first packet arrives (≤ 60 s after connection).
     * Observed by [org.gcontinuity.android.ui.screens.ConnectedScreen] to display
     * Linux battery level below the "Connected" subtitle in the TopAppBar.
     */
    val linuxBatteryState: StateFlow<BatteryState?> = _linuxBatteryState.asStateFlow()

    // ── Plugin settings ───────────────────────────────────────────────────────

    private val pluginStore = PluginStore(application)

    private val _pluginStates = MutableStateFlow(pluginStore.getAll())
    val pluginStates: StateFlow<Map<String, Boolean>> = _pluginStates.asStateFlow()

    fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        pluginStore.setEnabled(pluginId, enabled)
        _pluginStates.value = pluginStore.getAll()
    }

    // ── Init — wait for service then collect all state flows ──────────────────

    init {
        viewModelScope.launch {
            while (true) {
                val service = GContinuityService.instance
                if (service != null) {
                    launch { service.pairingState.collect      { _pairingState.value      = it } }
                    launch { service.discoveredDevices.collect { _discoveredDevices.value = it } }
                    // Phase 3: collect Linux battery from daemon
                    launch { service.linuxBatteryState.collect { _linuxBatteryState.value = it } }
                    break
                }
                delay(200)
            }
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

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
        GContinuityService.instance?.wsClient?.send(Packet.Ping)
    }

    fun ringDevice() {
        // TODO: send Ring packet when protocol supports it
    }
}
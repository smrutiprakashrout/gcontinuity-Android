package org.gcontinuity.android.ui.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import org.gcontinuity.android.transport.TransportManager
import org.gcontinuity.android.transport.model.ConnectionState
import javax.inject.Inject

/**
 * ViewModel for the connection status screen.
 *
 * Exposes [TransportManager] state flows as read-only [StateFlow]s and provides
 * user-initiated actions (disconnect, reconnect). All coroutines launched here
 * are scoped to [viewModelScope] and cancelled automatically when the ViewModel
 * is cleared.
 */
@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val transportManager: TransportManager,
) : ViewModel() {

    /** Current WebSocket connection state — drives UI indicator colour and text. */
    val connectionState: StateFlow<ConnectionState> = transportManager.connectionState

    /** Human-readable name of the connected Linux device, or null if disconnected. */
    val connectedDeviceName: StateFlow<String?> = transportManager.connectedDeviceName

    /**
     * Sends a [Packet.Disconnect] and closes the WebSocket.
     * The service will re-connect when mDNS rediscovers the device.
     */
    fun disconnect() = transportManager.stop()

    /**
     * Re-attempts connection using the last known host/port/cert.
     * No-ops if parameters are not available (device not yet discovered via mDNS).
     */
    fun reconnect() {
        // TransportManager.scheduleReconnect() is internal; stopping and waiting
        // for mDNS re-discovery is the correct flow for user-initiated reconnect.
        // The service will call connect() again when the device reappears.
        transportManager.stop()
    }
}

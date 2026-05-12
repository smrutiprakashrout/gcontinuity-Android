// org/gcontinuity/android/network/ws/WsClient.kt
//
// CHANGE vs previous version:
//   - Added sendRaw(json: String): Boolean — sends a raw JSON string over the
//     WebSocket without going through network.Packet serialization.
//     Used by BatteryPlugin to send transport.model.Packet.BatteryInfo JSON
//     over the Phase 1 socket (the only socket that reliably connects).

package org.gcontinuity.android.network.ws

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.gcontinuity.android.identity.DeviceIdentity
import org.gcontinuity.android.network.Packet
import org.gcontinuity.android.network.toJson
import org.gcontinuity.android.store.DeviceStore
import java.util.concurrent.atomic.AtomicBoolean

class WsClient(
    private val scope: CoroutineScope,
    private val store: DeviceStore,
    private val identity: DeviceIdentity,
    private val onPacketReceived: (Packet) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
) {
    private val TAG = "WsClient"

    private var webSocket: WebSocket? = null
    private val _isConnected = AtomicBoolean(false)
    val isConnected: Boolean get() = _isConnected.get()

    private var peerCertFingerprint: String? = null
    private var isFirstPairing = false
    private var peerCertFpGetter: (() -> String?)? = null

    fun connect(host: String, port: Int): Boolean {
        if (_isConnected.get()) {
            Log.d(TAG, "Already connected, skipping")
            return true
        }

        val trustedDevices = store.listTrustedDevices()
        val trustedFingerprints = trustedDevices.map { it.fingerprint }.toSet()

        val okHttpClient = if (trustedFingerprints.isEmpty()) {
            Log.i(TAG, "No trusted devices — using first-pairing mode (TOFU)")
            isFirstPairing = true
            val (client, getFp) = TlsHelper.buildFirstPairingOkHttpClient()
            peerCertFpGetter = getFp
            client
        } else {
            isFirstPairing = false
            TlsHelper.buildOkHttpClient(trustedFingerprints)
        }

        val hostPart = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
        val request = Request.Builder().url("wss://$hostPart:$port").build()

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket opened to $host:$port")
                webSocket = ws
                _isConnected.set(true)

                if (isFirstPairing) {
                    peerCertFingerprint = peerCertFpGetter?.invoke()
                    Log.i(TAG, "Peer cert fingerprint recorded: $peerCertFingerprint")
                }

                onConnected()
                sendHello()
                sendPairRequest()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.d(TAG, "Received: $text")
                Packet.fromJson(text)?.let { onPacketReceived(it) }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                _isConnected.set(false)
                webSocket = null
                onDisconnected()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
                _isConnected.set(false)
                webSocket = null
                onDisconnected()
            }
        }

        webSocket = okHttpClient.newWebSocket(request, listener)
        return true
    }

    /** Send a [network.Packet] over the WebSocket. */
    fun send(packet: Packet): Boolean {
        if (!_isConnected.get() || webSocket == null) return false
        val json = packet.toJson()
        Log.d(TAG, "Sending: $json")
        return webSocket!!.send(json)
    }

    /**
     * Send a raw JSON string over the WebSocket without going through
     * [network.Packet] serialization.
     *
     * Used by [org.gcontinuity.android.plugins.BatteryPlugin] to send
     * [org.gcontinuity.android.transport.model.Packet.BatteryInfo] JSON
     * over the Phase 1 socket — the only socket that reliably connects
     * to the Linux daemon.
     *
     * No-ops silently if not connected.
     */
    fun sendRaw(json: String): Boolean {
        if (!_isConnected.get() || webSocket == null) return false
        Log.d(TAG, "Sending raw: $json")
        return webSocket!!.send(json)
    }

    private fun sendHello() {
        send(
            Packet.Hello(
                device_id = identity.deviceId,
                name      = identity.deviceName,
                version   = 1,
            )
        )
    }

    private fun sendPairRequest() {
        send(
            Packet.PairRequest(
                device_id   = identity.deviceId,
                name        = identity.deviceName,
                fingerprint = identity.fingerprint,
            )
        )
    }

    fun disconnect(reason: String = "user_requested") {
        Log.i(TAG, "Disconnecting: $reason")
        send(Packet.Disconnect)
        webSocket?.close(1000, reason)
        _isConnected.set(false)
        webSocket = null
    }

    fun getPeerFingerprint(): String? = peerCertFingerprint
}
// org/gcontinuity/android/network/ws/WsClient.kt
//
// CHANGES FROM OLD VERSION:
//   1. `sendHello()` previously sent `Packet.Hello(..., fingerprint = ...)`.
//      Hello no longer carries fingerprint — removed.
//   2. Added `sendPairRequest()` called immediately after `sendHello()`.
//      This is the NEW pairing flow:
//        a) Android sends Hello {device_id, name, version}
//        b) Linux replies Hello {device_id, name, version}
//        c) Android sends PairRequest {device_id, name, fingerprint}
//        d) Linux replies PairAccept or PairReject
//   3. Removed `Packet.Disconnect(reason)` — Disconnect is now bare.
//      `disconnect()` sends `Packet.Disconnect` (no argument).

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

/**
 * Phase 1 WebSocket client — manages the raw OkHttp WebSocket connection
 * and the pairing-layer packet exchange.
 *
 * Connection flow:
 * 1. [connect] → TLS handshake → [onOpen] → [sendHello] + [sendPairRequest]
 * 2. Remote sends packets → [onPacketReceived] → [PairingManager.handlePacket]
 * 3. [disconnect] → sends bare Disconnect packet → closes socket
 */
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

        // No path suffix — tokio_tungstenite accepts any HTTP Upgrade path.
        // FIX: IPv6 addresses must be wrapped in [] in URLs (RFC 2732).
        // Without this, "wss://40e2:2020:...:52000" is parsed as invalid port.
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

                // Step 1: send bare Hello (no fingerprint).
                sendHello()
                // Step 2: send PairRequest with fingerprint so Linux can verify.
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
        return true // async — success is signalled by onOpen
    }

    fun send(packet: Packet): Boolean {
        if (!_isConnected.get() || webSocket == null) return false
        val json = packet.toJson()
        Log.d(TAG, "Sending: $json")
        return webSocket!!.send(json)
    }

    /**
     * Sends `{"type":"hello","device_id":...,"name":...,"version":1}`.
     * No fingerprint — fingerprint is sent in the follow-up [sendPairRequest].
     */
    private fun sendHello() {
        send(
            Packet.Hello(
                device_id = identity.deviceId,
                name      = identity.deviceName,
                version   = 1,
            )
        )
    }

    /**
     * Sends `{"type":"pair_request","device_id":...,"name":...,"fingerprint":...}`.
     * Called immediately after [sendHello] so Linux receives both in sequence.
     */
    private fun sendPairRequest() {
        send(
            Packet.PairRequest(
                device_id   = identity.deviceId,
                name        = identity.deviceName,
                fingerprint = identity.fingerprint,
            )
        )
    }

    /**
     * Sends a bare `{"type":"disconnect"}` packet, then closes the socket
     * with a normal close code (1000).
     */
    fun disconnect(reason: String = "user_requested") {
        Log.i(TAG, "Disconnecting: $reason")
        // Disconnect is bare — no reason field on the wire.
        send(Packet.Disconnect)
        webSocket?.close(1000, reason)
        _isConnected.set(false)
        webSocket = null
    }

    fun getPeerFingerprint(): String? = peerCertFingerprint
}
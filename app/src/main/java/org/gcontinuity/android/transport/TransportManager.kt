package org.gcontinuity.android.transport

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.gcontinuity.android.transport.model.ConnectionState
import org.gcontinuity.android.transport.model.Packet
import org.gcontinuity.android.transport.tls.TlsSocketFactory
import org.gcontinuity.android.util.ExponentialBackoff
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TransportManager"
private const val PROTOCOL_VERSION = 1

/**
 * Manages the persistent TLS WebSocket connection to gcontinuity-daemon.
 *
 * **Lifecycle**: Created by Hilt (singleton), injected into [GContinuityService].
 * [connect] is called by the service when mDNS discovers a trusted device.
 * [stop] is called when the service is destroyed or the user disconnects.
 *
 * **Reconnect strategy**: On any connection failure, exponential backoff kicks in
 * automatically via [scheduleReconnect]. The backoff resets to the initial delay
 * on each successful connection.
 *
 * **Thread-safety**: [sendPacket] is safe to call from any thread — OkHttp
 * queues send operations internally. State flows are updated from the OkHttp
 * dispatcher thread and are safe to collect from any coroutine.
 */
@Singleton
class TransportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val packetHandler: PacketHandler,
) {
    // ── Public state ──────────────────────────────────────────────────────────

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    /** Current state of the WebSocket control channel. */
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    /** Human-readable name of the currently connected Linux device, or null. */
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    // ── Private state ─────────────────────────────────────────────────────────

    private var webSocket: WebSocket? = null
    private var sessionToken: String? = null
    private val backoff = ExponentialBackoff()
    private var managerScope: CoroutineScope? = null

    // Stored to enable reconnect after network loss
    private var lastHost: String? = null
    private var lastPort: Int = 0
    private var lastCertSha256: ByteArray? = null

    private val isStopped = AtomicBoolean(false)

    private val json = Json {
        ignoreUnknownKeys  = true   // forward-compatibility: ignore future fields
        isLenient          = true
        encodeDefaults     = true
        classDiscriminator = "type"
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Initiates a WebSocket connection to the Linux daemon.
     *
     * Safe to call multiple times — if a connection is already active to the same
     * host, the call is ignored. If the host changed (e.g. IP reassigned by DHCP),
     * the existing socket is closed and a new connection is attempted.
     *
     * @param scope       Coroutine scope tied to the service lifecycle; cancelled
     *                    when the service is destroyed.
     * @param host        IP address or hostname of the Linux daemon.
     * @param port        TLS WebSocket port (default 52525 on the daemon).
     * @param certSha256  32-byte SHA-256 fingerprint of the daemon's self-signed cert.
     */
    fun connect(scope: CoroutineScope, host: String, port: Int, certSha256: ByteArray) {
        isStopped.set(false)
        managerScope = scope
        lastHost = host
        lastPort = port
        lastCertSha256 = certSha256
        scope.launch { openSocket(host, port, certSha256) }
    }

    /**
     * Cleanly disconnects and prevents any further reconnect attempts.
     * Sends a [Packet.Disconnect] before closing so the daemon can clean up.
     */
    fun stop() {
        isStopped.set(true)
        sendPacket(Packet.Disconnect)
        webSocket?.close(1000, "Service stopped")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedDeviceName.value = null
        managerScope = null
    }

    // ── Connection logic ──────────────────────────────────────────────────────

    private fun openSocket(host: String, port: Int, certSha256: ByteArray) {
        _connectionState.value = ConnectionState.CONNECTING

        val (sslFactory, trustManager) = TlsSocketFactory.create(certSha256)

        val client = OkHttpClient.Builder()
            .sslSocketFactory(sslFactory, trustManager)
            // IP-based LAN connection — hostname verification is irrelevant for
            // self-signed certs. Cert identity is verified via SHA-256 pinning above.
            .hostnameVerifier { _, _ -> true }
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            // Zero read timeout — the connection is persistent; OkHttp pings handle liveness.
            .readTimeout(0, TimeUnit.SECONDS)
            .build()

        // FIX: IPv6 addresses must be wrapped in [] in URLs (RFC 2732).
        val hostPart = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
        val request = Request.Builder()
            .url("wss://$hostPart:$port/gcontinuity")
            .build()

        webSocket = client.newWebSocket(request, buildListener())
    }

    private fun buildListener() = object : WebSocketListener() {

        override fun onOpen(ws: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket opened")
            backoff.reset()
            _connectionState.value = ConnectionState.CONNECTED

            // SessionResume if we have a token from a previous session; else Hello
            val token = sessionToken
            val encoded = if (token != null) {
                json.encodeToString(Packet.serializer(), Packet.SessionResume(token))
            } else {
                val deviceId = Settings.Secure.getString(
                    context.contentResolver, Settings.Secure.ANDROID_ID
                ) ?: Build.MODEL
                json.encodeToString(
                    Packet.serializer(),
                    Packet.Hello(device_id = deviceId, name = Build.MODEL, version = PROTOCOL_VERSION)
                )
            }
            ws.send(encoded)
        }

        override fun onMessage(ws: WebSocket, text: String) {
            Log.v(TAG, "RX: $text")
            try {
                val packet = json.decodeFromString(Packet.serializer(), text)
                managerScope?.launch { handleIncoming(ws, packet) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse packet: $text", e)
            }
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "WebSocket failure: ${t.message}")
            _connectionState.value = ConnectionState.RECONNECTING
            scheduleReconnect()
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closed: $code / $reason")
            // Code 1000 = normal closure (user pressed Disconnect or daemon shutdown)
            if (code != 1000) {
                _connectionState.value = ConnectionState.RECONNECTING
                scheduleReconnect()
            } else {
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    private fun scheduleReconnect() {
        if (isStopped.get()) return
        val delayMs = backoff.nextDelayMs()
        Log.i(TAG, "Reconnecting in ${delayMs}ms (attempt ${backoff.currentAttempt})")
        managerScope?.launch {
            delay(delayMs)
            if (!isStopped.get()) {
                val h    = lastHost ?: return@launch
                val cert = lastCertSha256 ?: return@launch
                openSocket(h, lastPort, cert)
            }
        }
    }

    // ── Packet I/O ────────────────────────────────────────────────────────────

    /**
     * Encodes [packet] to JSON and queues it for sending over the WebSocket.
     * Thread-safe — OkHttp serialises send operations internally.
     * No-ops silently if the socket is not connected.
     */
    fun sendPacket(packet: Packet) {
        val encoded = json.encodeToString(Packet.serializer(), packet)
        Log.v(TAG, "TX: $encoded")
        webSocket?.send(encoded)
    }

    private suspend fun handleIncoming(ws: WebSocket, packet: Packet) {
        when (packet) {
            is Packet.Ack -> {
                Log.d(TAG, "ACK received")
            }
            is Packet.Ping -> {
                Log.d(TAG, "Ping → Pong")
                ws.send(json.encodeToString(Packet.serializer(), Packet.Pong))
            }
            is Packet.Pong -> {
                Log.d(TAG, "Pong received — connection healthy")
            }
            is Packet.Disconnect -> {
                Log.i(TAG, "Daemon requested disconnect")
                ws.close(1000, "Daemon disconnect")
                _connectionState.value = ConnectionState.DISCONNECTED
            }
            else -> packetHandler.handle(packet)
        }
    }
}
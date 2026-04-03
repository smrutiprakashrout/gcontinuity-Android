package org.gcontinuity.android.network.reconnect

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.gcontinuity.android.network.DeviceInfo
import org.gcontinuity.android.network.mdns.MdnsDiscovery
import org.gcontinuity.android.network.ws.WsClient
import org.gcontinuity.android.store.DeviceStore
import java.util.concurrent.ConcurrentHashMap

class ReconnectManager(
    private val scope: CoroutineScope,
    private val store: DeviceStore,
    private val wsClient: WsClient,
    private val mdns: MdnsDiscovery,
) {
    private val TAG = "ReconnectManager"

    private var reconnectJob: Job? = null
    private val addressCache = ConcurrentHashMap<String, Pair<String, Int>>()
    private val connectMutex = Mutex()

    fun onNetworkRestored() {
        Log.i(TAG, "WiFi restored — triggering mDNS rescan + reconnect")
        scope.launch { mdns.restartDiscovery() }
        attemptAllTrustedFromCache()
    }

    fun onNetworkLost() {
        Log.i(TAG, "WiFi lost — stopping reconnect attempts")
        reconnectJob?.cancel()
        reconnectJob = null
        addressCache.clear()
    }

    fun onDeviceDiscovered(device: DeviceInfo) {
        addressCache[device.deviceId] = Pair(device.host, device.port)
        val connected = wsClient.isConnected
        if (store.isTrusted(device.deviceId) && !connected) {
            startReconnectJob(device.deviceId, device.host, device.port)
        }
    }

    private fun attemptAllTrustedFromCache() {
        addressCache.forEach { (deviceId, addr) ->
            val connected = wsClient.isConnected
            if (store.isTrusted(deviceId) && !connected) {
                val (host, port) = addr
                startReconnectJob(deviceId, host, port)
            }
        }
    }

    private fun startReconnectJob(deviceId: String, host: String, port: Int) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var attempt = 0
            val maxAttempts = 20
            while (attempt < maxAttempts && isActive) {
                val alreadyConnected = wsClient.isConnected
                if (alreadyConnected) {
                    Log.i(TAG, "Already connected, stopping reconnect job")
                    return@launch
                }
                connectMutex.withLock {
                    val stillDisconnected = !wsClient.isConnected
                    if (stillDisconnected) {
                        Log.i(TAG, "Reconnect attempt $attempt to $host:$port")
                        wsClient.connect(host, port)
                    }
                }
                val nowConnected = wsClient.isConnected
                if (nowConnected) {
                    Log.i(TAG, "Connected to $host:$port!")
                    return@launch
                }
                val delayMs = when (attempt) {
                    0 -> 500L
                    1 -> 2_000L
                    2 -> 4_000L
                    3 -> 8_000L
                    4 -> 16_000L
                    else -> 30_000L
                }
                attempt++
                if (delayMs > 0) delay(delayMs)
            }
            if (attempt >= maxAttempts) {
                Log.w(TAG, "Gave up reconnecting after $maxAttempts attempts")
            }
        }
    }

    fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }
}

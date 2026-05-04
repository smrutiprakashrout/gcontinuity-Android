package org.gcontinuity.android.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides a [Flow] representing whether a validated Wi-Fi network is available.
 *
 * [TransportManager] subscribes to this flow to trigger reconnection automatically
 * when Wi-Fi is restored after a loss. Uses [callbackFlow] to bridge the
 * [ConnectivityManager.NetworkCallback] callback API into the coroutine world.
 * [distinctUntilChanged] suppresses duplicate emissions when the state doesn't change.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Emits `true` when a validated Wi-Fi network is reachable, `false` otherwise.
     * Emits the current state immediately upon collection, then updates reactively.
     * The flow is cold — each collector registers its own [NetworkCallback].
     */
    val isWifiAvailable: Flow<Boolean> = callbackFlow {
        val cm = context.getSystemService(ConnectivityManager::class.java)

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network)  { trySend(true) }
            override fun onLost(network: Network)       { trySend(false) }
            override fun onUnavailable()                { trySend(false) }
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()

        cm.registerNetworkCallback(request, callback)

        // Emit initial state synchronously so the first collector doesn't have
        // to wait for a callback to determine the current Wi-Fi status.
        val active = cm.activeNetwork
        val caps   = active?.let { cm.getNetworkCapabilities(it) }
        val hasWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                      caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        trySend(hasWifi)

        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}

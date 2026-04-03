package org.gcontinuity.android.network.reconnect

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

sealed class NetworkEvent {
    object WiFiAvailable : NetworkEvent()
    object WiFiLost : NetworkEvent()
}

class NetworkWatcher(private val context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun register(onEvent: (NetworkEvent) -> Unit) {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i("NetworkWatcher", "WiFi available")
                onEvent(NetworkEvent.WiFiAvailable)
            }

            override fun onLost(network: Network) {
                Log.i("NetworkWatcher", "WiFi lost")
                onEvent(NetworkEvent.WiFiLost)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    onEvent(NetworkEvent.WiFiAvailable)
                }
            }
        }
        cm.registerNetworkCallback(request, callback!!)
    }

    fun unregister() {
        callback?.let { cm.unregisterNetworkCallback(it) }
        callback = null
    }
}

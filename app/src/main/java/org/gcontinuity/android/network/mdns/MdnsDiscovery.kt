package org.gcontinuity.android.network.mdns

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.gcontinuity.android.network.DeviceInfo
import java.util.concurrent.ConcurrentHashMap

class MdnsDiscovery(
    private val context: Context,
    private val scope: CoroutineScope,
    private val deviceId: String,
    private val deviceName: String,
    private val port: Int = 52000,
    private val onDeviceFound: (DeviceInfo) -> Unit,
    private val onDeviceLost: (String) -> Unit,
) {
    private val TAG = "MdnsDiscovery"
    private val SERVICE_TYPE = "_gcontinuity._tcp"

    private val nsd = context.getSystemService(NsdManager::class.java)
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    var lastEventTimeMs = System.currentTimeMillis()
    private val discoveryTimestamps = ConcurrentHashMap<String, Long>()

    fun refreshTimestamp(deviceId: String) {
        discoveryTimestamps[deviceId] = System.currentTimeMillis()
    }

    fun getStaleDeviceIds(ttlMs: Long = 60_000L): List<String> {
        val now = System.currentTimeMillis()
        return discoveryTimestamps.filter { (now - it.value) > ttlMs }.keys.toList()
    }

    suspend fun clearAndRescan() {
        discoveryTimestamps.clear()
        stop()
        delay(600)
        start()
    }

    fun start() {
        Log.i(TAG, "Starting mDNS discovery")
        registerOwnService()
        startBrowsing()
    }

    fun stop() {
        stopBrowsing()
        unregisterOwnService()
    }

    suspend fun restartDiscovery() {
        Log.i(TAG, "Restarting mDNS discovery")
        stop()
        delay(500)
        start()
    }

    private fun registerOwnService() {
        val info = NsdServiceInfo().apply {
            serviceName = "gcontinuity-android-$deviceId"
            serviceType = SERVICE_TYPE
            this.port = this@MdnsDiscovery.port
            setAttribute("id", deviceId)
            setAttribute("name", deviceName)
            setAttribute("version", "1")
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "Service registered: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Registration failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "Service unregistered: ${info.serviceName}")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Unregistration failed: $errorCode")
            }
        }

        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener!!)
        } catch (e: Exception) {
            Log.e(TAG, "registerService exception", e)
        }
    }

    private fun unregisterOwnService() {
        registrationListener?.let {
            try {
                nsd.unregisterService(it)
            } catch (e: Exception) {
                Log.w(TAG, "unregisterService exception", e)
            }
            registrationListener = null
        }
    }

    private fun startBrowsing() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Start discovery failed: $errorCode — retrying in 3s")
                scope.launch {
                    delay(3000)
                    startBrowsing()
                }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed: $errorCode")
            }
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "Discovery started for $serviceType")
                lastEventTimeMs = System.currentTimeMillis()
            }
            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Discovery stopped for $serviceType")
            }
            override fun onServiceFound(info: NsdServiceInfo) {
                lastEventTimeMs = System.currentTimeMillis()
                Log.d(TAG, "Service found: ${info.serviceName}")
                if (info.serviceType == SERVICE_TYPE || info.serviceType.startsWith(SERVICE_TYPE)) {
                    try {
                        nsd.resolveService(info, buildResolveListener())
                    } catch (e: Exception) {
                        Log.e(TAG, "resolveService exception", e)
                    }
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                lastEventTimeMs = System.currentTimeMillis()
                Log.d(TAG, "Service lost: ${info.serviceName}")
                // Extract deviceId from service name format "gcontinuity-android-<uuid>"
                val lostId = info.serviceName.removePrefix("gcontinuity-android-")
                onDeviceLost(lostId)
            }
        }

        discoveryListener = listener
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "discoverServices exception", e)
        }
    }

    private fun stopBrowsing() {
        discoveryListener?.let {
            try {
                nsd.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.w(TAG, "stopServiceDiscovery exception", e)
            }
            discoveryListener = null
        }
    }

    private fun buildResolveListener(): NsdManager.ResolveListener {
        return object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed: $errorCode for ${info.serviceName}")
                scope.launch {
                    delay(1000)
                    try {
                        nsd.resolveService(info, buildResolveListener())
                    } catch (e: Exception) {
                        Log.e(TAG, "Retry resolveService exception", e)
                    }
                }
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                lastEventTimeMs = System.currentTimeMillis()
                Log.i(TAG, "Service resolved: ${info.serviceName} @ ${info.host?.hostAddress}:${info.port}")

                val foundDeviceId = info.attributes["id"]?.let { String(it) } ?: run {
                    Log.w(TAG, "No 'id' attribute in resolved service, skipping")
                    return
                }
                val foundDeviceName = info.attributes["name"]?.let { String(it) } ?: info.serviceName
                val host = info.host?.hostAddress ?: run {
                    Log.w(TAG, "No host address in resolved service, skipping")
                    return
                }

                discoveryTimestamps[foundDeviceId] = System.currentTimeMillis()
                onDeviceFound(DeviceInfo(foundDeviceId, foundDeviceName, "", host, info.port))
            }
        }
    }
}

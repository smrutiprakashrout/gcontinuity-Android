package org.gcontinuity.android.network.mdns

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MdnsWatchdog(
    private val scope: CoroutineScope,
    private val discovery: MdnsDiscovery,
    private val timeoutMs: Long = 90_000L,
) {
    private val TAG = "MdnsWatchdog"
    private var watchdogJob: Job? = null

    fun start() {
        watchdogJob = scope.launch {
            while (isActive) {
                delay(timeoutMs)
                val silent = System.currentTimeMillis() - discovery.lastEventTimeMs > timeoutMs
                if (silent) {
                    Log.w(TAG, "NsdManager silent for ${timeoutMs}ms — restarting")
                    discovery.restartDiscovery()
                }
            }
        }
        Log.i(TAG, "Watchdog started (timeout=${timeoutMs}ms)")
    }

    fun stop() {
        watchdogJob?.cancel()
        watchdogJob = null
        Log.i(TAG, "Watchdog stopped")
    }
}

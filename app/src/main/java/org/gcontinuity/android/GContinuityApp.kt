package org.gcontinuity.android

import android.app.Application
import android.content.Intent
import android.os.Build
import org.gcontinuity.android.identity.IdentityManager
import org.gcontinuity.android.service.GContinuityService

class GContinuityApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Must happen before any service or component tries to generate TLS certs.
        IdentityManager.installBouncyCastle()

        // Start the foreground service at Application level so it is alive
        // before any Activity runs, survives rotations and back-stack clears,
        // and starts on boot (via BootReceiver) without an Activity ever opening.
        // Calling startForegroundService when the service is already running is
        // safe — it calls onStartCommand again but not onCreate.
        val intent = Intent(this, GContinuityService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

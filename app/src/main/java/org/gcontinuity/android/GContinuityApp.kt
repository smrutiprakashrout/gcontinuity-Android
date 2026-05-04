package org.gcontinuity.android

import android.app.Application
import android.content.Intent
import android.os.Build
import dagger.hilt.android.HiltAndroidApp
import org.gcontinuity.android.identity.IdentityManager
import org.gcontinuity.android.service.GContinuityService
import org.gcontinuity.android.transport.tls.TlsSocketFactory

/**
 * Application entry point. Annotated with [@HiltAndroidApp] to trigger Hilt's
 * component hierarchy generation. Initialises security providers in the correct
 * order before any service or component attempts TLS or cert operations.
 */
@HiltAndroidApp
class GContinuityApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // 1. Install full BouncyCastle provider (replaces Android's stripped-down BC).
        //    Required for X.509 self-signed certificate generation in IdentityManager.
        IdentityManager.installBouncyCastle()

        // 2. Install Conscrypt as the highest-priority TLS provider for TLS 1.3 support.
        //    Must follow BouncyCastle so both providers coexist correctly.
        TlsSocketFactory.installConscrypt()

        // 3. Start the foreground service at Application level so it is alive
        //    before any Activity runs, survives rotations and back-stack clears,
        //    and starts on boot (via BootReceiver) without an Activity ever opening.
        //    Calling startForegroundService when the service is already running is
        //    safe — it triggers onStartCommand again but not onCreate.
        val intent = Intent(this, GContinuityService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

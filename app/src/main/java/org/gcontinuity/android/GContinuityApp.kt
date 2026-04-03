package org.gcontinuity.android

import android.app.Application
import org.gcontinuity.android.identity.IdentityManager

class GContinuityApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Replace Android's stripped-down BouncyCastle with the full standalone provider.
        // Must happen before any service or component tries to generate TLS certs.
        IdentityManager.installBouncyCastle()
    }
}

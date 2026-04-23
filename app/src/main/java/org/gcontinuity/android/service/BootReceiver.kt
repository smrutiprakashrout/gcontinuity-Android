package org.gcontinuity.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            // Fired before the credential-encrypted storage is unlocked.
            // Lets us start the service as early as possible on locked devices.
            action == "android.intent.action.LOCKED_BOOT_COMPLETED"
        ) {
            Log.i("BootReceiver", "Boot event ($action) — starting GContinuityService")
            val serviceIntent = Intent(context, GContinuityService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
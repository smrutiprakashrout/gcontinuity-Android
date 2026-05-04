package org.gcontinuity.android.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.gcontinuity.android.MainActivity
import org.gcontinuity.android.R
import javax.inject.Inject
import javax.inject.Singleton

private const val CHANNEL_ID   = "gcontinuity_transport"
private const val CHANNEL_NAME = "GContinuity Connection"

/**
 * Singleton helper for building and posting the persistent foreground-service notification.
 *
 * Extracted from [GContinuityService] to be injectable and testable in isolation.
 * Handles the [NotificationChannel] creation (required API 26+) and the runtime
 * POST_NOTIFICATIONS permission check (required API 33+).
 */
@Singleton
class NotificationHelper @Inject constructor() {

    companion object {
        const val NOTIFICATION_ID    = 1001
        const val ACTION_DISCONNECT  = "org.gcontinuity.ACTION_DISCONNECT"
    }

    /**
     * Creates the [NotificationChannel] if it has not been created yet.
     * Safe to call multiple times — the system is idempotent for existing channels.
     * Must be called before [buildNotification] is used as a foreground notification.
     */
    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Persistent connection status for GContinuity"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    /**
     * Builds the persistent foreground notification with [statusText] as the body.
     *
     * Includes a tap-to-open action (launches [MainActivity]) and a "Disconnect"
     * action that sends [ACTION_DISCONNECT] to [GContinuityService].
     */
    fun buildNotification(context: Context, statusText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disconnectIntent = PendingIntent.getService(
            context, 1,
            Intent(context, GContinuityService::class.java).apply {
                action = ACTION_DISCONNECT
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("GContinuity")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Disconnect", disconnectIntent)
            .build()
    }

    /**
     * Posts the notification with the given [statusText].
     *
     * On API 33+ checks POST_NOTIFICATIONS permission before posting; silently
     * skips if the permission has not been granted (the foreground notification
     * posted via [startForeground] is always shown regardless of this permission,
     * but updates to it require the grant on API 33+).
     */
    fun postUpdate(context: Context, statusText: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(context, statusText))
    }
}

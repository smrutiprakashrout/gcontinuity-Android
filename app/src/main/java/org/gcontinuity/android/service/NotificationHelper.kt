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
import org.gcontinuity.android.SendTextActivity
import javax.inject.Inject
import javax.inject.Singleton

private const val CHANNEL_ID   = "gcontinuity_transport"
private const val CHANNEL_NAME = "GContinuity Connection"

/**
 * Builds and posts the persistent foreground-service notification.
 *
 * ## When connected ([deviceName] non-null):
 * Title : device name  e.g. "SM-G990B2"
 * Body  : "Connected"
 * Actions: [Send Clipboard] [Send File] [Run Command]
 *
 * ## When not connected ([deviceName] null):
 * Title : "GContinuity"
 * Body  : "Scanning…" / "No device connected"
 * Actions: (none)
 *
 * ## Permission note
 * POST_NOTIFICATIONS is a runtime permission on Android 13+.
 * [MainActivity] requests it in onCreate before any notification is posted.
 * [postUpdate] silently skips if the permission is not yet granted.
 * The initial [startForeground] call in [GContinuityService] is always shown
 * regardless — it is system-managed, not app-managed.
 */
@Singleton
class NotificationHelper @Inject constructor() {

    companion object {
        const val NOTIFICATION_ID       = 1001

        // Quick-action intent actions — received by MainActivity
        const val ACTION_OPEN_CLIPBOARD = "org.gcontinuity.OPEN_CLIPBOARD"
        const val ACTION_OPEN_FILES     = "org.gcontinuity.OPEN_FILES"
        const val ACTION_OPEN_COMMANDS  = "org.gcontinuity.OPEN_COMMANDS"
        const val EXTRA_NAV_ACTION      = "nav_action"
    }

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
     * Builds the persistent foreground notification.
     *
     * @param statusText  Body text shown under the title.
     * @param deviceName  Connected device name, or null when not connected.
     * When non-null, shown as title and quick-action buttons appear.
     */
    fun buildNotification(
        context: Context,
        statusText: String,
        deviceName: String? = null,
    ): Notification {
        // Tap notification body → open app
        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(deviceName ?: "GContinuity")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        // Quick-action buttons — only shown when a device is connected
        if (deviceName != null) {

            // 👉 Phase 3.2 FIX: Route to the invisible ghost Activity to bypass Android 10 clipboard restrictions
            builder.addAction(
                0, "Send Clipboard",
                PendingIntent.getActivity(
                    context,
                    2,
                    Intent(context, SendTextActivity::class.java).apply {
                        action = "org.gcontinuity.ACTION_MANUAL_SEND_CLIPBOARD"
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )

            builder.addAction(
                0, "Send File",
                quickActionIntent(context, ACTION_OPEN_FILES, requestCode = 3),
            )
            builder.addAction(
                0, "Run Command",
                quickActionIntent(context, ACTION_OPEN_COMMANDS, requestCode = 4),
            )
        }

        return builder.build()
    }

    /**
     * Posts an updated notification.
     *
     * @param statusText  Body text.
     * @param deviceName  Connected device name, or null when not connected.
     */
    fun postUpdate(
        context: Context,
        statusText: String,
        deviceName: String? = null,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(context, statusText, deviceName))
    }

    // ── Private ───────────────────────────────────────────────────────────────

    /**
     * Builds a [PendingIntent] that opens [MainActivity] with the given [action].
     * Uses FLAG_ACTIVITY_SINGLE_TOP so if the app is already in foreground,
     * [MainActivity.onNewIntent] is called instead of recreating the activity.
     */
    private fun quickActionIntent(
        context: Context,
        action: String,
        requestCode: Int,
    ): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            this.action = action
            putExtra(EXTRA_NAV_ACTION, action)
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
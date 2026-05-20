package org.gcontinuity.android.plugins

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import org.gcontinuity.android.MainActivity
import org.gcontinuity.android.R
import org.gcontinuity.android.service.NotificationHelper
import org.gcontinuity.android.store.PluginStore
import org.gcontinuity.android.transport.model.Packet
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG                     = "ClipboardPlugin"
private const val CLIPBOARD_NOTIF_ID      = 2001
private const val CLIPBOARD_CHANNEL_ID   = "gcontinuity_transport"

@Singleton
class ClipboardPlugin @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pluginStore: PluginStore,
) : FeaturePlugin {

    override val pluginId = "clipboard_sync_send"

    var wsClientSender: ((String) -> Unit)? = null

    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as ClipboardManager

    private val json = Json {
        ignoreUnknownKeys  = true
        encodeDefaults     = true
        classDiscriminator = "type"
    }

    @Volatile private var lastSentHash:     ByteArray = ByteArray(32)
    @Volatile private var lastReceivedHash: ByteArray = ByteArray(32)

    override fun start(scope: CoroutineScope) {
        Log.i(TAG, "Starting clipboard plugin")
        if (pluginStore.isEnabled("clipboard_sync_send")) {
            clipboardManager.addPrimaryClipChangedListener(clipboardListener)
            Log.i(TAG, "Clipboard listener registered (send enabled)")
        }
    }

    override fun stop() {
        clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        Log.i(TAG, "Stopped")
    }

    override suspend fun onPacket(packet: Packet) {
        if (packet !is Packet.ClipboardSync) return
        if (!pluginStore.isEnabled("clipboard_sync_receive")) {
            Log.d(TAG, "Receive disabled — ignoring ClipboardSync")
            return
        }

        val content = packet.data
        if (content.isBlank()) return

        val hash = sha256(content)

        if (hash.contentEquals(lastReceivedHash)) {
            Log.d(TAG, "RX ClipboardSync: duplicate — ignored")
            return
        }
        val current = currentClipboardText()
        if (current != null && sha256(current).contentEquals(hash)) {
            Log.d(TAG, "RX ClipboardSync: local clipboard matches — ignored")
            lastReceivedHash = hash
            lastSentHash     = hash
            return
        }

        lastReceivedHash = hash
        lastSentHash     = hash

        val clip = ClipData.newPlainText("gcontinuity", content)
        clipboardManager.setPrimaryClip(clip)
        Log.d(TAG, "RX ClipboardSync from Linux: ${content.length} chars written to clipboard")
    }

    fun sendClipboardNow() {
        val content = currentClipboardText() ?: run {
            Log.w(TAG, "sendClipboardNow: clipboard empty")
            return
        }
        trySend(content)
    }

    /** Called directly by the native OS context menu */
    fun sendSelectedText(text: String) {
        Log.d(TAG, "Sending text directly from system context menu")
        trySend(text)
    }

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!pluginStore.isEnabled("clipboard_sync_send")) return@OnPrimaryClipChangedListener

        val content = currentClipboardText()

        // Always show the fallback notification if the user copies normally
        // instead of using the "Send to Linux" context menu
        if (content != null && content.isNotBlank()) {
            val hash = sha256(content)
            if (hash.contentEquals(lastSentHash) || hash.contentEquals(lastReceivedHash)) {
                return@OnPrimaryClipChangedListener
            }
        }
        showSyncNotification()
        Log.d(TAG, "Standard clipboard copy detected — fallback notification shown")
    }

    private fun trySend(content: String) {
        val sender = wsClientSender ?: run {
            Log.w(TAG, "wsClientSender not set — cannot send clipboard")
            return
        }
        val hash = sha256(content)
        if (hash.contentEquals(lastSentHash) || hash.contentEquals(lastReceivedHash)) {
            Log.d(TAG, "trySend: loop guard hit — not sending")
            return
        }
        lastSentHash = hash

        val packet  = Packet.ClipboardSync(mime = "text/plain", data = content)
        val encoded = json.encodeToString(Packet.serializer(), packet)
        sender(encoded)
        Log.d(TAG, "TX ClipboardSync: ${content.length} chars sent to Linux")
    }

    private fun showSyncNotification() {
        val openIntent = PendingIntent.getActivity(
            context, 10,
            Intent(context, MainActivity::class.java).apply {
                flags  = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                action = NotificationHelper.ACTION_OPEN_CLIPBOARD
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CLIPBOARD_CHANNEL_ID)
            .setContentTitle("Clipboard changed")
            .setContentText("Tap to sync clipboard to Linux")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(CLIPBOARD_NOTIF_ID, notification)
    }

    private fun currentClipboardText(): String? =
        try {
            clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()
        } catch (e: Exception) {
            null
        }

    private fun sha256(content: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
}
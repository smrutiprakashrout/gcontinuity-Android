package org.gcontinuity.android

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import org.gcontinuity.android.plugins.ClipboardPlugin
import javax.inject.Inject

@AndroidEntryPoint
class SendTextActivity : ComponentActivity() {

    @Inject lateinit var clipboardPlugin: ClipboardPlugin

    // Flag to track if this was launched from the Notification button
    private var isManualTrigger = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Disable enter animation to keep the ghost activity invisible
        overridePendingTransition(0, 0)

        // Phase 3.2: Intercept the notification button tap
        if (intent.action == "org.gcontinuity.ACTION_MANUAL_SEND_CLIPBOARD") {
            isManualTrigger = true
            // We must STOP here and wait for onWindowFocusChanged before reading the clipboard!
            return
        }

        // Standard Phase 3.3 Share Sheet / Highlight Context Menu Logic
        var textToSend: String? = null

        if (intent.action == Intent.ACTION_SEND && "text/plain" == intent.type) {
            textToSend = intent.getStringExtra(Intent.EXTRA_TEXT)
        } else if (intent.action == Intent.ACTION_PROCESS_TEXT) {
            textToSend = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        }

        if (!textToSend.isNullOrBlank()) {
            val trimmedText = textToSend.trim()
            if (Patterns.WEB_URL.matcher(trimmedText).matches()) {
                clipboardPlugin.sendUrl(trimmedText)
                Toast.makeText(this, "Opening on Linux...", Toast.LENGTH_SHORT).show()
            } else {
                clipboardPlugin.sendSelectedText(trimmedText)
                Toast.makeText(this, "Sent to Linux clipboard", Toast.LENGTH_SHORT).show()
            }
        }

        finish()
    }

    // Android 10+ requires the app to officially have window focus before reading the clipboard
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isManualTrigger) {
            clipboardPlugin.sendClipboardNow()
            Toast.makeText(this, "Clipboard sent to Linux", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun finish() {
        super.finish()
        // Disable exit animation so it vanishes instantly
        overridePendingTransition(0, 0)
    }
}
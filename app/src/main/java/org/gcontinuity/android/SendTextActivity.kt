package org.gcontinuity.android

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import org.gcontinuity.android.plugins.ClipboardPlugin
import javax.inject.Inject

@AndroidEntryPoint
class SendTextActivity : ComponentActivity() {

    @Inject lateinit var clipboardPlugin: ClipboardPlugin

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        var textToSend: String? = null

        // Scenario A: User used the standard Share Sheet (ACTION_SEND)
        if (intent.action == Intent.ACTION_SEND && "text/plain" == intent.type) {
            textToSend = intent.getStringExtra(Intent.EXTRA_TEXT)
        }
        // Scenario B: User highlighted text and used the context menu (PROCESS_TEXT)
        else if (intent.action == Intent.ACTION_PROCESS_TEXT) {
            textToSend = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        }

        // If we successfully grabbed text from either method, fire it off
        if (!textToSend.isNullOrBlank()) {
            clipboardPlugin.sendSelectedText(textToSend)
            Toast.makeText(this, "Sent to Linux", Toast.LENGTH_SHORT).show()
        }

        // Close instantly so the user stays in their current app
        finish()
    }
}
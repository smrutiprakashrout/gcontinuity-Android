package org.gcontinuity.android.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class Plugin(
    val id: String,
    val title: String,
    val description: String,
    val enabledByDefault: Boolean = true,
)

val ALL_PLUGINS = listOf(
    Plugin("battery_report",          "Battery report",               "Periodically report battery status"),
    // Phase 3.2: two directional clipboard toggles
    Plugin("clipboard_sync_send",     "Send clipboard to Linux",      "Automatically send Android clipboard to Linux"),
    Plugin("clipboard_sync_receive",  "Receive clipboard from Linux", "Automatically receive Linux clipboard on Android"),
    Plugin("connectivity_report",     "Connectivity report",          "Report network signal strength and status"),
    Plugin("contacts_sync",           "Contacts Synchroniser",        "Allow synchronising the device's contacts book"),
    Plugin("filesystem_expose",       "Filesystem expose",            "Allows to browse this device's filesystem remotely"),
    Plugin("find_my_phone",           "Find my phone",                "Rings this device so you can find it"),
    Plugin("find_remote_device",      "Find remote device",           "Ring your remote device"),
    Plugin("media_player_control",    "Media Player Control",         "Control your phone's media players from your computer"),
    Plugin("multimedia_receiver",     "Multimedia Receiver",          "Control the computer's media player from your phone"),
    Plugin("mousepad",                "Remote input",                 "Use your phone as a touchpad and keyboard"),
    Plugin("notifications_sync",      "Notification sync",            "Sync notifications between devices"),
    Plugin("ping",                    "Ping",                         "Send and receive ping packets"),
    Plugin("presenter",               "Presentation remote",          "Use your phone as a presentation remote"),
    Plugin("run_command",             "Run command",                  "Run predefined commands on your computer"),
    Plugin("share",                   "Share and receive",            "Send and receive files between devices"),
    Plugin("telephony",               "Telephone integration",        "Manage calls and SMS from your computer"),
)

class PluginStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "gcontinuity_plugins",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun isEnabled(pluginId: String): Boolean {
        val default = ALL_PLUGINS.find { it.id == pluginId }?.enabledByDefault ?: true
        return prefs.getBoolean(pluginId, default)
    }

    fun setEnabled(pluginId: String, enabled: Boolean) {
        prefs.edit().putBoolean(pluginId, enabled).apply()
    }

    fun getAll(): Map<String, Boolean> =
        ALL_PLUGINS.associate { it.id to isEnabled(it.id) }
}
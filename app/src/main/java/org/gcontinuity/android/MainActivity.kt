package org.gcontinuity.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.gcontinuity.android.pairing.PairingState
import org.gcontinuity.android.service.GContinuityService
import org.gcontinuity.android.service.NotificationHelper
import org.gcontinuity.android.ui.screens.ConnectedScreen
import org.gcontinuity.android.ui.screens.PairingScreen
import org.gcontinuity.android.ui.screens.PluginSettingsScreen
import org.gcontinuity.android.ui.screens.ScanScreen
import org.gcontinuity.android.ui.screens.SettingsScreen
import org.gcontinuity.android.ui.theme.GContinuityTheme
import org.gcontinuity.android.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    // ── POST_NOTIFICATIONS runtime permission (Android 13+) ───────────────────
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* service continues regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()
        requestOverlayPermission()
        requestBatteryExemption()

        val initialState = GContinuityService.instance?.pairingState?.value
        val startDest = if (initialState is PairingState.PairedConnected) "connected" else "scan"

        setContent {
            GContinuityTheme {
                val navController = rememberNavController()
                val vm: MainViewModel = viewModel()
                val pairingState by vm.pairingState.collectAsState()

                // Handle notification quick-action intent on launch
                LaunchedEffect(Unit) {
                    handleNavIntent(intent, navController, vm)
                }

                LaunchedEffect(pairingState) {
                    val current = navController.currentDestination?.route
                    when (pairingState) {
                        is PairingState.PairedConnected -> {
                            if (current != "connected" && current != "plugin_settings") {
                                navController.navigate("connected") {
                                    popUpTo("scan") { inclusive = true }
                                }
                            }
                        }
                        is PairingState.AwaitingPair -> {
                            if (current != "pairing") {
                                navController.navigate("pairing") {
                                    popUpTo("scan")
                                }
                            }
                        }
                        is PairingState.Reconnecting,
                        is PairingState.Scanning,
                        is PairingState.Idle,
                        is PairingState.Error,
                        is PairingState.Connecting -> {
                            if (current == "connected" || current == "pairing") {
                                navController.navigate("scan") {
                                    popUpTo(0) { inclusive = true }
                                }
                            }
                        }
                    }
                }

                NavHost(navController = navController, startDestination = startDest) {

                    composable("scan") {
                        ScanScreen(viewModel = vm, navController = navController)
                    }

                    composable("pairing") {
                        val state = pairingState
                        if (state is PairingState.AwaitingPair) {
                            PairingScreen(
                                device           = state.device,
                                linuxFingerprint = state.linuxFingerprint,
                                onAccept         = { vm.acceptPairing() },
                                onReject         = { vm.rejectPairing() }
                            )
                        }
                    }

                    composable("connected") {
                        val state = pairingState
                        val device = when (state) {
                            is PairingState.PairedConnected -> state.device
                            is PairingState.Reconnecting    -> state.device
                            else                            -> return@composable
                        }
                        ConnectedScreen(
                            device               = device,
                            pairingState         = state,
                            viewModel            = vm,
                            onOpenPluginSettings = { navController.navigate("plugin_settings") },
                            onDisconnect         = {
                                vm.disconnect()
                                navController.navigate("scan") {
                                    popUpTo(0) { inclusive = true }
                                }
                            }
                        )
                    }

                    composable("plugin_settings") {
                        PluginSettingsScreen(
                            viewModel = vm,
                            onBack    = { navController.popBackStack() }
                        )
                    }

                    composable("settings") {
                        SettingsScreen(
                            viewModel = vm,
                            onBack    = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    /**
     * Called when app is already running and a notification quick-action
     * intent arrives (FLAG_ACTIVITY_SINGLE_TOP).
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // The LaunchedEffect(Unit) won't re-trigger for onNewIntent.
        // Handle clipboard send directly here when app is already open.
        if (intent.action == NotificationHelper.ACTION_OPEN_CLIPBOARD) {
            GContinuityService.instance?.let { service ->
                // App is now in foreground — clipboard is readable.
                service.pluginManager.sendClipboardNow()
            }
        }
    }

    // ── Permission helpers ────────────────────────────────────────────────────

    /**
     * Request POST_NOTIFICATIONS on Android 13+.
     * Foreground service notification requires this on API 33+.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * Request SYSTEM_ALERT_WINDOW (Draw over other apps) for the clipboard overlay.
     * This is a special permission — cannot use registerForActivityResult.
     * Redirects user to the system settings page if not already granted.
     * Only shown once; subsequent launches skip if already granted.
     */
    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (_: Exception) {
                    // Settings page not available on this device — overlay will
                    // fall back to heads-up notification silently.
                }
            }
        }
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                } catch (_: Exception) { }
            }
        }
    }

    // ── Navigation intent handler ─────────────────────────────────────────────

    /**
     * Routes notification quick-action intents to the correct in-app screen.
     * Called from [LaunchedEffect] on first launch.
     * [onNewIntent] handles the case when app is already running.
     */
    private fun handleNavIntent(
        intent: Intent?,
        navController: NavController,
        vm: MainViewModel,
    ) {
        when (intent?.action) {
            NotificationHelper.ACTION_OPEN_CLIPBOARD -> {
                // App just came to foreground — clipboard is now readable.
                // Send clipboard immediately then navigate to connected screen.
                GContinuityService.instance?.pluginManager?.sendClipboardNow()
                navController.navigate("connected") {
                    popUpTo("scan") { inclusive = false }
                }
            }
            NotificationHelper.ACTION_OPEN_FILES -> {
                navController.navigate("connected") {
                    popUpTo("scan") { inclusive = false }
                }
                // TODO: open file picker inside ConnectedScreen (Phase 4)
            }
            NotificationHelper.ACTION_OPEN_COMMANDS -> {
                navController.navigate("connected") {
                    popUpTo("scan") { inclusive = false }
                }
                // TODO: navigate to commands tab inside ConnectedScreen (Phase 5)
            }
        }
    }
}
package org.gcontinuity.android

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.gcontinuity.android.pairing.PairingState
import org.gcontinuity.android.service.GContinuityService
import org.gcontinuity.android.ui.screens.ConnectedScreen
import org.gcontinuity.android.ui.screens.PairingScreen
import org.gcontinuity.android.ui.screens.PluginSettingsScreen
import org.gcontinuity.android.ui.screens.ScanScreen
import org.gcontinuity.android.ui.screens.SettingsScreen
import org.gcontinuity.android.ui.theme.GContinuityTheme
import org.gcontinuity.android.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestBatteryExemption()

        val initialState = GContinuityService.instance?.pairingState?.value
        val startDest = if (initialState is PairingState.PairedConnected) "connected" else "scan"

        setContent {
            GContinuityTheme {
                val navController = rememberNavController()
                val vm: MainViewModel = viewModel()
                val pairingState by vm.pairingState.collectAsState()

                LaunchedEffect(pairingState) {
                    val current = navController.currentDestination?.route

                    when (pairingState) {

                        is PairingState.PairedConnected -> {
                            // Navigate to connected from anywhere except already
                            // being there or being in plugin_settings on top of it.
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

                        // FIX — white screen bug:
                        // Previously Reconnecting/Idle/Scanning/Error/Connecting were
                        // all unhandled here. The "connected" composable only rendered
                        // when state == PairedConnected, so any other state on that
                        // route produced a blank white page with no navigation away.
                        //
                        // Now: if we're on "connected" or "pairing" and the state is
                        // no longer one of those, immediately go back to scan.
                        // When the device comes back in range, ReconnectManager fires,
                        // pairingState becomes PairedConnected again, and we navigate
                        // back to "connected" automatically — just like KDE Connect.
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
                        ScanScreen(
                            viewModel = vm,
                            navController = navController
                        )
                    }

                    composable("pairing") {
                        val state = pairingState
                        if (state is PairingState.AwaitingPair) {
                            PairingScreen(
                                device = state.device,
                                linuxFingerprint = state.linuxFingerprint,
                                onAccept = { vm.acceptPairing() },
                                onReject = { vm.rejectPairing() }
                            )
                        }
                    }

                    composable("connected") {
                        val state = pairingState
                        // FIX — show the screen for both connected AND reconnecting.
                        // ConnectedScreen already has a reconnecting banner for this case.
                        // This prevents the blank white page during brief reconnect flashes.
                        val device = when (state) {
                            is PairingState.PairedConnected -> state.device
                            is PairingState.Reconnecting    -> state.device
                            else                            -> return@composable
                        }
                        ConnectedScreen(
                            device = device,
                            pairingState = state,
                            viewModel = vm,
                            onOpenPluginSettings = {
                                navController.navigate("plugin_settings")
                            },
                            onDisconnect = {
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
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable("settings") {
                        SettingsScreen(
                            viewModel = vm,
                            onBack = { navController.popBackStack() }
                        )
                    }
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
}

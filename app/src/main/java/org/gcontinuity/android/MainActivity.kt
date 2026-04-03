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
import org.gcontinuity.android.ui.screens.ScanScreen
import org.gcontinuity.android.ui.screens.SettingsScreen
import org.gcontinuity.android.ui.theme.GContinuityTheme
import org.gcontinuity.android.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Start foreground service if not running
        startServiceIfNeeded()

        // Request battery optimization exemption on first launch
        requestBatteryExemption()

        val initialState = GContinuityService.instance?.pairingState?.value
        val startDest = if (initialState is PairingState.PairedConnected) "connected" else "scan"

        setContent {
            GContinuityTheme {
                val navController = rememberNavController()
                val vm: MainViewModel = viewModel()

                val pairingState by vm.pairingState.collectAsState()
                val discoveredDevices by vm.discoveredDevices.collectAsState()

                LaunchedEffect(pairingState) {
                    when (pairingState) {
                        is PairingState.AwaitingPair -> navController.navigate("pairing") {
                            popUpTo("scan")
                        }
                        is PairingState.PairedConnected -> navController.navigate("connected") {
                            popUpTo("scan") { inclusive = true }
                        }
                        is PairingState.Idle,
                        is PairingState.Scanning,
                        is PairingState.Error -> navController.navigate("scan") {
                            popUpTo(0) { inclusive = true }
                        }
                        else -> Unit
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
                        if (state is PairingState.PairedConnected) {
                            ConnectedScreen(
                                device = state.device,
                                pairingState = state,
                                onDisconnect = { vm.disconnect() },
                                onOpenSettings = { navController.navigate("settings") }
                            )
                        }
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

    private fun startServiceIfNeeded() {
        if (GContinuityService.instance == null) {
            val intent = Intent(this, GContinuityService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // Some devices don't support this intent — ignore
                }
            }
        }
    }
}
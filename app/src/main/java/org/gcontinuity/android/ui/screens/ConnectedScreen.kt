package org.gcontinuity.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.gcontinuity.android.network.DeviceInfo
import org.gcontinuity.android.pairing.PairingState
import org.gcontinuity.android.ui.components.StatusIndicator
import org.gcontinuity.android.viewmodel.MainViewModel

// ── Feature grid data ─────────────────────────────────────────────────────────

private data class FeatureItem(
    val label: String,
    val icon: ImageVector,
)

private val FEATURES = listOf(
    FeatureItem("Send files",              Icons.Outlined.UploadFile),
    FeatureItem("Send clipboard",          Icons.Outlined.ContentPaste),
    FeatureItem("Presentation\nremote",    Icons.Outlined.Slideshow),
    FeatureItem("Multimedia\ncontrol",     Icons.Outlined.PlayCircle),
    FeatureItem("Run Command",             Icons.Outlined.Terminal),
    FeatureItem("Remote input",            Icons.Outlined.TouchApp),
)

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectedScreen(
    device: DeviceInfo,
    pairingState: PairingState,
    viewModel: MainViewModel,
    onOpenPluginSettings: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var showOverflowMenu by remember { mutableStateOf(false) }
    var showEncryptionDialog by remember { mutableStateOf(false) }
    var showUnpairDialog by remember { mutableStateOf(false) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(16.dp))

                // Header
                ListItem(
                    headlineContent = {
                        Text(
                            text = device.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    supportingContent = {
                        Text(
                            text = "GContinuity",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = {
                        StatusIndicator(
                            state = pairingState,
                            modifier = Modifier.size(10.dp)
                        )
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                NavigationDrawerItem(
                    icon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
                    label = { Text("Plugin settings") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenPluginSettings()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                    label = { Text("Encryption info") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        showEncryptionDialog = true
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )

                Spacer(Modifier.weight(1f))
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                NavigationDrawerItem(
                    icon = {
                        Icon(
                            Icons.Rounded.LinkOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    label = {
                        Text("Unpair device", color = MaterialTheme.colorScheme.error)
                    },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        showUnpairDialog = true
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Rounded.Menu, contentDescription = "Open menu")
                        }
                    },
                    title = {
                        Column {
                            Text(
                                text = device.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = if (pairingState is PairingState.Reconnecting)
                                    "Reconnecting…" else "Connected",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (pairingState is PairingState.Reconnecting)
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    actions = {
                        // Overflow menu
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                        ) {
                            DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Outlined.VolumeUp, null) },
                                text = { Text("Ring") },
                                onClick = {
                                    showOverflowMenu = false
                                    viewModel.ringDevice()
                                }
                            )
                            DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Outlined.Notifications, null) },
                                text = { Text("Send ping") },
                                onClick = {
                                    showOverflowMenu = false
                                    viewModel.sendPing()
                                }
                            )
                            DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Rounded.Tune, null) },
                                text = { Text("Plugin settings") },
                                onClick = {
                                    showOverflowMenu = false
                                    onOpenPluginSettings()
                                }
                            )
                            DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Outlined.Lock, null) },
                                text = { Text("Encryption info") },
                                onClick = {
                                    showOverflowMenu = false
                                    showEncryptionDialog = true
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        Icons.Rounded.LinkOff, null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                text = {
                                    Text("Unpair", color = MaterialTheme.colorScheme.error)
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    showUnpairDialog = true
                                }
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // Reconnecting banner — matches existing ConnectedScreen style
                if (pairingState is PairingState.Reconnecting) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                    ) {
                        Text(
                            text = "Connection lost — reconnecting...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // Feature grid
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(FEATURES) { feature ->
                        FeatureCard(feature = feature)
                    }
                }
            }
        }
    }

    // ── Encryption info dialog ────────────────────────────────────────────
    if (showEncryptionDialog) {
        AlertDialog(
            onDismissRequest = { showEncryptionDialog = false },
            icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
            title = { Text("Encryption Info") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "SHA-256 fingerprint",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Reuse existing FingerprintDisplay component
                    org.gcontinuity.android.ui.components.FingerprintDisplay(
                        fingerprint = device.fingerprint,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showEncryptionDialog = false }) { Text("Close") }
            }
        )
    }

    // ── Unpair confirmation dialog ────────────────────────────────────────
    if (showUnpairDialog) {
        AlertDialog(
            onDismissRequest = { showUnpairDialog = false },
            icon = {
                Icon(
                    Icons.Rounded.LinkOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("Unpair device?") },
            text = {
                Text("This will remove ${device.name} from your trusted devices. You'll need to pair again to reconnect.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUnpairDialog = false
                        viewModel.unpairDevice(device)
                        onDisconnect()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Unpair") }
            },
            dismissButton = {
                TextButton(onClick = { showUnpairDialog = false }) { Text("Cancel") }
            }
        )
    }
}

// ── Feature card ──────────────────────────────────────────────────────────────

@Composable
private fun FeatureCard(feature: FeatureItem) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = feature.icon,
                contentDescription = feature.label,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = feature.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                lineHeight = 20.sp,
            )
        }
    }
}

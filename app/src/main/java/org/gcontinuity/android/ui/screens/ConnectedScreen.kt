package org.gcontinuity.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Assignment
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.gcontinuity.android.network.DeviceInfo
import org.gcontinuity.android.pairing.PairingState
import org.gcontinuity.android.ui.components.StatusIndicator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectedScreen(
    device: DeviceInfo,
    pairingState: PairingState,
    onDisconnect: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connected") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "Settings"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
        ) {
            // Reconnecting banner
            if (pairingState is PairingState.Reconnecting) {
                Column(modifier = Modifier.fillMaxWidth()) {
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

            Spacer(modifier = Modifier.height(16.dp))

            // Connection card
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusIndicator(
                        state = pairingState,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = device.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Connected",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onDisconnect) {
                        Icon(
                            imageVector = Icons.Rounded.LinkOff,
                            contentDescription = "Disconnect"
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Fingerprint
            Text(
                text = "Fingerprint: ${device.fingerprint.take(17)}...",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Active Features",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Feature rows — disabled, hinting at roadmap
            Column(modifier = Modifier.alpha(0.5f)) {
                ListItem(
                    headlineContent = { Text("Clipboard sharing") },
                    supportingContent = { Text("Coming soon") },
                    leadingContent = {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                    },
                    trailingContent = {
                        Switch(checked = false, onCheckedChange = null, enabled = false)
                    }
                )
                ListItem(
                    headlineContent = { Text("Notification sync") },
                    supportingContent = { Text("Coming soon") },
                    leadingContent = {
                        Icon(Icons.Rounded.Notifications, contentDescription = null)
                    },
                    trailingContent = {
                        Switch(checked = false, onCheckedChange = null, enabled = false)
                    }
                )
                ListItem(
                    headlineContent = { Text("File transfer") },
                    supportingContent = { Text("Coming soon") },
                    leadingContent = {
                        Icon(Icons.Rounded.Assignment, contentDescription = null)
                    },
                    trailingContent = {
                        Switch(checked = false, onCheckedChange = null, enabled = false)
                    }
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            ) {
                Text("Disconnect")
            }
        }
    }
}

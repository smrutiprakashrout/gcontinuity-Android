package org.gcontinuity.android.ui.connection

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.gcontinuity.android.transport.model.ConnectionState

private val GreenConnected   = Color(0xFF4CAF50)
private val AmberTransitional = Color(0xFFFFC107)
private val RedDisconnected  = Color(0xFFF44336)

/**
 * Connection status screen.
 *
 * Displays the current [ConnectionState] as an animated coloured dot, the device
 * name when connected, a linear progress indicator during transitions, and
 * contextual action buttons (Disconnect / Reconnect).
 *
 * @param viewModel Hilt-provided [ConnectionViewModel]; overridable for previews.
 */
@Composable
fun ConnectionScreen(
    viewModel: ConnectionViewModel = hiltViewModel(),
) {
    val state       by viewModel.connectionState.collectAsStateWithLifecycle()
    val deviceName  by viewModel.connectedDeviceName.collectAsStateWithLifecycle()

    val dotColor by animateColorAsState(
        targetValue = when (state) {
            ConnectionState.CONNECTED    -> GreenConnected
            ConnectionState.CONNECTING,
            ConnectionState.RECONNECTING -> AmberTransitional
            ConnectionState.DISCONNECTED -> RedDisconnected
        },
        animationSpec = tween(durationMillis = 400),
        label = "dotColor",
    )

    val dotScale by animateFloatAsState(
        targetValue = if (state == ConnectionState.CONNECTED) 1.2f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "dotScale",
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color    = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier            = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text  = "Transport Layer",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )

            // ── Status card ───────────────────────────────────────────────────
            Card(
                modifier  = Modifier.fillMaxWidth(),
                shape     = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Row(
                    modifier            = Modifier.padding(20.dp),
                    verticalAlignment   = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // Animated status dot
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .scale(dotScale)
                            .clip(CircleShape)
                            .background(dotColor),
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text  = state.label,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (state == ConnectionState.CONNECTED && deviceName != null) {
                            Text(
                                text  = deviceName!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Icon(
                        imageVector = if (state.isActive) Icons.Default.Link
                                      else Icons.Default.LinkOff,
                        contentDescription = null,
                        tint = dotColor,
                    )
                }
            }

            // ── Progress indicator ────────────────────────────────────────────
            if (state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING) {
                LinearProgressIndicator(
                    modifier  = Modifier.fillMaxWidth(),
                    strokeCap = StrokeCap.Round,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Action buttons ────────────────────────────────────────────────
            if (state == ConnectionState.CONNECTED) {
                OutlinedButton(
                    onClick  = { viewModel.disconnect() },
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.outlinedButtonColors(
                        contentColor = RedDisconnected,
                    ),
                ) {
                    Icon(Icons.Default.LinkOff, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Disconnect")
                }
            }

            if (state == ConnectionState.DISCONNECTED) {
                Button(
                    onClick  = { viewModel.reconnect() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Link, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Reconnect")
                }
            }
        }
    }
}

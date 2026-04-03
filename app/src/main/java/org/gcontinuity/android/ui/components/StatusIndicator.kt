package org.gcontinuity.android.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.gcontinuity.android.pairing.PairingState

@Composable
fun StatusIndicator(
    state: PairingState,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "status_pulse")

    val shouldPulse = state is PairingState.Reconnecting || state is PairingState.Scanning

    val alpha by infiniteTransition.animateFloat(
        initialValue = if (shouldPulse) 0.7f else 1f,
        targetValue = if (shouldPulse) 1f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha_pulse"
    )

    val color = when (state) {
        is PairingState.PairedConnected -> Color(0xFF34C759)
        is PairingState.Reconnecting -> Color(0xFFFF9500)
        is PairingState.Scanning -> Color(0xFF1A73E8)
        else -> Color(0xFF8E9099)
    }

    Canvas(modifier = modifier) {
        drawCircle(
            color = color,
            alpha = if (shouldPulse) alpha else 1f
        )
    }
}

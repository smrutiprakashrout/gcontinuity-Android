package org.gcontinuity.android.ui.components

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

@Composable
fun FingerprintDisplay(fingerprint: String, modifier: Modifier = Modifier) {
    // Split into list of "XX" pairs then group 4 per line with 2 per group
    val formatted = remember(fingerprint) {
        val pairs = fingerprint.split(":")
        pairs
            .chunked(8) { lineChunk ->
                lineChunk.chunked(2) { groupChunk -> groupChunk.joinToString(":") }
                    .joinToString("  ")
            }
            .joinToString("\n")
    }

    SelectionContainer(modifier = modifier) {
        Text(
            text = formatted,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            letterSpacing = 1.sp,
            lineHeight = 22.sp
        )
    }
}

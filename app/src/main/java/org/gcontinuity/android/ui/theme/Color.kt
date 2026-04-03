package org.gcontinuity.android.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Brand primary: Google Blue #1A73E8
val Blue40 = Color(0xFF1A73E8)
val Blue80 = Color(0xFF9ECAFF)
val Blue20 = Color(0xFF004BA0)

val BlueGrey10 = Color(0xFF0D1B2A)
val BlueGrey20 = Color(0xFF1A2D40)
val BlueGrey30 = Color(0xFF263D52)
val BlueGrey90 = Color(0xFFD0E4F4)

val LightColorScheme = lightColorScheme(
    primary = Blue40,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4FF),
    onPrimaryContainer = Blue20,
    secondary = Color(0xFF1565C0),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E4FF),
    onSecondaryContainer = Color(0xFF001B4F),
    tertiary = Color(0xFF00875A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF9EF5C1),
    onTertiaryContainer = Color(0xFF00210F),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF8FAFB),
    onBackground = Color(0xFF1A1C1E),
    surface = Color.White,
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474F),
    outline = Color(0xFF74777F),
)

val DarkColorScheme = darkColorScheme(
    primary = Blue80,
    onPrimary = Blue20,
    primaryContainer = Color(0xFF004BA0),
    onPrimaryContainer = Color(0xFFD3E4FF),
    secondary = Color(0xFFACC7FF),
    onSecondary = Color(0xFF002E6B),
    secondaryContainer = Color(0xFF004494),
    onSecondaryContainer = Color(0xFFD6E4FF),
    tertiary = Color(0xFF83D9A5),
    onTertiary = Color(0xFF00391D),
    tertiaryContainer = Color(0xFF00522B),
    onTertiaryContainer = Color(0xFF9EF5C1),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = BlueGrey10,
    onBackground = Color(0xFFE2E2E6),
    surface = BlueGrey20,
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = BlueGrey30,
    onSurfaceVariant = Color(0xFFC4C7D0),
    outline = Color(0xFF8E9099),
)
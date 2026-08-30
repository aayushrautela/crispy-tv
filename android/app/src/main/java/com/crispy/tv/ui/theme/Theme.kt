package com.crispy.tv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CrispyDarkColors = darkColorScheme(
    primary = Color(0xFFF56E3C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD95A30),
    onPrimaryContainer = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFFF56E3C),
    secondary = Color(0xFFF56E3C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD95A30),
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = Color(0xFFF56E3C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD95A30),
    onTertiaryContainer = Color(0xFFFFFFFF),
    background = Color(0xFF141414),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF1F1F1F),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFB3B3B3),
    surfaceContainer = Color(0xFF1F1F1F),
    surfaceContainerHigh = Color(0xFF2A2A2A),
    surfaceContainerHighest = Color(0xFF333333),
    surfaceContainerLow = Color(0xFF141414),
    surfaceContainerLowest = Color(0xFF0A0A0A),
    surfaceTint = Color(0xFFF56E3C),
    surfaceDim = Color(0xFF0A0A0A),
    surfaceBright = Color(0xFF2A2A2A),
    outline = Color(0xFF333333),
    outlineVariant = Color(0xFF262626),
    error = Color(0xFFE8455C),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFB03040),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFECE1C6),
    inverseOnSurface = Color(0xFF141414),
    scrim = Color(0xFF000000),
)

@Composable
fun CrispyRewriteTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CrispyDarkColors, content = content)
}

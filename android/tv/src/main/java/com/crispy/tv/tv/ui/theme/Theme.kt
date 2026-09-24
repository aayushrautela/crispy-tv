package com.crispy.tv.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

val CrispySpinner = Color(0xFFF56E3C)

private val CrispyTvDarkColors = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF141414),
    primaryContainer = Color(0xFF2A2A2A),
    onPrimaryContainer = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFFB3B3B3),
    onSecondary = Color(0xFF141414),
    secondaryContainer = Color(0xFF333333),
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = Color(0xFF888888),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF2A2A2A),
    onTertiaryContainer = Color(0xFFFFFFFF),
    background = Color(0xFF141414),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF1F1F1F),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFB3B3B3),
    surfaceTint = Color(0xFFFFFFFF),
    inverseSurface = Color(0xFFECE1C6),
    inverseOnSurface = Color(0xFF141414),
    border = Color(0xFF333333),
    borderVariant = Color(0xFF262626),
    error = Color(0xFFE8455C),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFB03040),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xFF000000),
)

@Composable
fun CrispyTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CrispyTvDarkColors, content = content)
}

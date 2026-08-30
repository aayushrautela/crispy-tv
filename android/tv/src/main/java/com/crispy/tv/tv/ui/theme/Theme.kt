package com.crispy.tv.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val CrispyTvDarkColors = darkColorScheme(
    primary = Color(0xFFF56E3C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD95A30),
    onPrimaryContainer = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFFF56E3C),
    secondary = Color(0xFFF56E3C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD95A30),
    onSecondaryContainer = Color(0xFFFFFFFF),
    background = Color(0xFF141414),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF1F1F1F),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFB3B3B3),
    border = Color(0xFF333333),
    borderVariant = Color(0xFF262626),
    error = Color(0xFFE8455C),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFB03040),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun CrispyTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CrispyTvDarkColors, content = content)
}

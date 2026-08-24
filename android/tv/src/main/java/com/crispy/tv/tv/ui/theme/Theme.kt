package com.crispy.tv.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val CrispyDarkColors = darkColorScheme(
    primary = Color(0xFFFFC400),
    onPrimary = Color(0xFF2B2000),
    primaryContainer = Color(0xFF433000),
    onPrimaryContainer = Color(0xFFFFE08C),
    secondary = Color(0xFFE7C34D),
    onSecondary = Color(0xFF3B2F00),
    secondaryContainer = Color(0xFF574600),
    onSecondaryContainer = Color(0xFFFFE08C),
    tertiary = Color(0xFFFFB95C),
    onTertiary = Color(0xFF311300),
    background = Color(0xFF15120A),
    onBackground = Color(0xFFECE1C6),
    surface = Color(0xFF1D1A12),
    onSurface = Color(0xFFECE1C6),
    surfaceVariant = Color(0xFF4D4632),
    onSurfaceVariant = Color(0xFFD1C5A6),
    border = Color(0xFF9B8F72),
    borderVariant = Color(0xFF4D4632),
)

@Composable
fun CrispyTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CrispyDarkColors, content = content)
}

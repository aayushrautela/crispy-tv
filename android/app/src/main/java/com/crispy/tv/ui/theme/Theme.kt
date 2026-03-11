package com.crispy.tv.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color

private val LightColors = yellowAccentLightColorScheme()
private val DarkColors = darkColorScheme(
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
    outline = Color(0xFF9B8F72),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CrispyRewriteTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialExpressiveTheme(colorScheme = colorScheme) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}

private fun yellowAccentLightColorScheme(): ColorScheme =
    lightColorScheme(
        primary = Color(0xFF7A5B00),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFE08C),
        onPrimaryContainer = Color(0xFF261900),
        secondary = Color(0xFF6B5E2F),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFF4E2A4),
        onSecondaryContainer = Color(0xFF221B00),
        tertiary = Color(0xFF855318),
        onTertiary = Color(0xFFFFFFFF),
        background = Color(0xFFFFF8ED),
        onBackground = Color(0xFF1F1B13),
        surface = Color(0xFFFFF8ED),
        onSurface = Color(0xFF1F1B13),
        surfaceVariant = Color(0xFFECE1C6),
        onSurfaceVariant = Color(0xFF4C4636),
        outline = Color(0xFF7D7663),
    )

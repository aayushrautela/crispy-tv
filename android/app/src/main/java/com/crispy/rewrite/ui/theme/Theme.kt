package com.crispy.rewrite.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.expressivetheme.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.expressivetheme.MaterialExpressiveTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF0054A5),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF5A5F71),
    background = Color(0xFFF7F8FB),
    surface = Color(0xFFFFFFFF)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9EC9FF),
    onPrimary = Color(0xFF002A54),
    secondary = Color(0xFFC2C6DC),
    background = Color(0xFF121317),
    surface = Color(0xFF1A1C20)
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CrispyRewriteTheme(content: @Composable () -> Unit) {
    val colors = if (androidx.compose.foundation.isSystemInDarkTheme()) DarkColors else LightColors
    MaterialExpressiveTheme(colorScheme = colors) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}

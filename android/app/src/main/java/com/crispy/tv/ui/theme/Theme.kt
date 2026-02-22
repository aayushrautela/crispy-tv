package com.crispy.tv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
    MaterialExpressiveTheme(colorScheme = DarkColors) {
        MaterialTheme(colorScheme = DarkColors, content = content)
    }
}

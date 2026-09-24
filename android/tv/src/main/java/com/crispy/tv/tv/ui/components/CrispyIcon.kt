package com.crispy.tv.tv.ui.components

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

@Composable
fun CrispyIcon(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    autoMirror: Boolean = false,
) {
    val mirrored = autoMirror && LocalLayoutDirection.current == LayoutDirection.Rtl
    Icon(
        painter = painter,
        contentDescription = contentDescription,
        modifier = if (mirrored) modifier.graphicsLayer { scaleX = -1f } else modifier,
        tint = tint,
    )
}
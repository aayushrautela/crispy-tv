package com.crispy.tv.tv.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

private const val HorizontalFadeWidthFraction = 0.45f
private const val BottomFadeStartFraction = 0.82f

/**
 * Proportional hero scrim: a left readability fade reaching 45% of the hero width
 * and a bottom fade into the page background starting at 82% of the hero height.
 * Scales with the hero so the fade geometry is identical at any hero size.
 */
fun Modifier.tvHeroScrim(backgroundColor: Color): Modifier = drawWithCache {
    val fadeWidth = size.width * HorizontalFadeWidthFraction
    val bottomStartY = size.height * BottomFadeStartFraction
    val horizontalFade = Brush.horizontalGradient(
        colorStops = arrayOf(
            0.0f to backgroundColor,
            0.22f to backgroundColor.copy(alpha = 0.86f),
            0.46f to backgroundColor.copy(alpha = 0.56f),
            0.76f to backgroundColor.copy(alpha = 0.16f),
            1.0f to Color.Transparent,
        ),
        startX = 0f,
        endX = fadeWidth,
    )
    val bottomFade = Brush.verticalGradient(
        colorStops = arrayOf(
            0.0f to Color.Transparent,
            0.40f to backgroundColor.copy(alpha = 0.25f),
            0.75f to backgroundColor.copy(alpha = 0.65f),
            1.0f to backgroundColor,
        ),
        startY = bottomStartY,
        endY = size.height,
    )
    onDrawBehind {
        drawRect(brush = horizontalFade, size = Size(fadeWidth, size.height))
        drawRect(
            brush = bottomFade,
            topLeft = Offset(0f, bottomStartY),
            size = Size(size.width, size.height - bottomStartY),
        )
    }
}

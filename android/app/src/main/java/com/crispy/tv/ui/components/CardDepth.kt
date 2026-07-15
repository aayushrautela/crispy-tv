package com.crispy.tv.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

fun Modifier.cardDepth(
    shape: Shape,
    edgeStrength: Float = 18f,
    sheenStrength: Float = 10f,
    edgeCoverage: Float = 60f,
): Modifier {
    val edge = edgeStrength.coerceIn(0f, 100f) / 100f
    val sheen = sheenStrength.coerceIn(0f, 100f) / 100f
    val coverage = edgeCoverage.coerceIn(0f, 100f) / 100f

    val withEdge = if (edge > 0f) {
        this.then(
            androidx.compose.foundation.border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = edge),
                        Color.White.copy(alpha = edge * (0.33f + 0.67f * coverage)),
                        Color.White.copy(alpha = edge * coverage),
                    ),
                ),
                shape = shape,
            ),
        )
    } else {
        this
    }

    return if (sheen > 0f) {
        withEdge.drawWithContent {
            drawContent()
            val sheenHeight = size.height * 0.22f
            if (sheenHeight > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = sheen),
                            Color.Transparent,
                        ),
                        startY = 0f,
                        endY = sheenHeight,
                    ),
                    size = Size(size.width, sheenHeight),
                )
            }
        }
    } else {
        withEdge
    }
}

package com.crispy.tv.details

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

internal fun entranceAlpha(progress: Float, startFraction: Float): Float =
    ((progress - startFraction) / (1f - startFraction).coerceAtLeast(0.01f)).coerceIn(0f, 1f)

internal fun Modifier.entranceFade(progress: () -> Float, startFraction: Float): Modifier =
    graphicsLayer {
        val alpha = entranceAlpha(progress(), startFraction)
        this.alpha = alpha
        translationY = (1f - alpha) * 6.dp.toPx()
    }

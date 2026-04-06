package com.crispy.tv.playerui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp

@Composable
internal fun rememberOverlayPadding(minPadding: Dp): PaddingValues {
    val safeDrawing = WindowInsets.safeDrawing
    val safeGestures = WindowInsets.safeGestures
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    val leftPx = maxOf(
        safeDrawing.getLeft(density, layoutDirection),
        safeGestures.getLeft(density, layoutDirection),
    )
    val rightPx = maxOf(
        safeDrawing.getRight(density, layoutDirection),
        safeGestures.getRight(density, layoutDirection),
    )
    val topPx = maxOf(
        safeDrawing.getTop(density),
        safeGestures.getTop(density),
    )
    val bottomPx = maxOf(
        safeDrawing.getBottom(density),
        safeGestures.getBottom(density),
    )

    val left = with(density) { leftPx.toDp() }
    val right = with(density) { rightPx.toDp() }
    val top = with(density) { topPx.toDp() }
    val bottom = with(density) { bottomPx.toDp() }

    return remember(left, right, top, bottom, minPadding) {
        PaddingValues(
            start = maxOf(minPadding, left),
            end = maxOf(minPadding, right),
            top = maxOf(minPadding, top),
            bottom = maxOf(minPadding, bottom),
        )
    }
}

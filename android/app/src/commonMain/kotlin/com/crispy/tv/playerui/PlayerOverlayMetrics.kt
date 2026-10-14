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

/**
 * The resting edge for a bottom sheet's horizontal [LazyRow]s: at least
 * [minPadding], widened to clear a display cutout or gesture area on either
 * side.
 *
 * This is the horizontal half of [rememberOverlayPadding]. The sheets sit over
 * the overlay rather than inside its padded box, so they cannot reuse its
 * padding -- and an outer `Modifier.padding` is the wrong shape anyway, since
 * it would clip the scroll bleed: the padding must live in the row's
 * `contentPadding` so the first and last items rest inset-aware while a fling
 * still runs to the screen edge.
 */
@Composable
internal fun rememberHorizontalCutoutPadding(minPadding: Dp): PaddingValues {
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

    val left = with(density) { leftPx.toDp() }
    val right = with(density) { rightPx.toDp() }

    return remember(left, right, minPadding) {
        PaddingValues(
            start = maxOf(minPadding, left),
            end = maxOf(minPadding, right),
        )
    }
}

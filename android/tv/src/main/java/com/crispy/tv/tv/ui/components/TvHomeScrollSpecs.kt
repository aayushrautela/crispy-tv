@file:OptIn(ExperimentalFoundationApi::class)

package com.crispy.tv.tv.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import kotlin.math.abs

/**
 * Pins the focused rail's top edge at [topInsetPx] below the top of the rows
 * viewport so every rail header lands on the same line when navigating down.
 */
fun tvRowsBringIntoViewSpec(
    topInsetPx: Float,
    canScrollBackward: () -> Boolean,
): BringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        if (abs(offset - topInsetPx) < 1f) return 0f
        val distance = offset - topInsetPx
        if (distance < 0f && !canScrollBackward()) return 0f
        return distance
    }
}

/**
 * Scrolls the focused card so its leading edge sits at [startInsetPx] from the
 * rail's start edge, clamping so cards wider than the viewport stay fully visible.
 */
fun tvRailBringIntoViewSpec(
    startInsetPx: Float,
    isRtl: Boolean,
): BringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val childSize = abs(size)
        if (isRtl) {
            val initialTarget = containerSize - startInsetPx
            val targetForTrailingEdge = if (childSize <= containerSize && initialTarget < childSize) {
                childSize
            } else {
                initialTarget
            }
            return (offset + size) - targetForTrailingEdge
        }
        val space = containerSize - startInsetPx
        val leading = if (childSize <= containerSize && space < childSize) {
            containerSize - childSize
        } else {
            startInsetPx
        }
        return offset - leading
    }
}

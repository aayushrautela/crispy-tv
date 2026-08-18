package com.crispy.tv.streams

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * Per-surface styling for the shared stream selector so each host (Player, Details, Home)
 * keeps its existing look. No UX change: each caller passes the chrome it already used.
 */
data class SelectorChrome(
    val accentColor: Color,
    val onAccentColor: Color,
    val useCrispyImageModel: Boolean = false,
    val showSkeletonChips: Boolean = false,
    val loadingIndicatorSize: Dp = 48.dp,
    val scrimColor: Color? = null,
)

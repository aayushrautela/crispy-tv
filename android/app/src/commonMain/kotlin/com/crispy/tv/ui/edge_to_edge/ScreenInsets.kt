package com.crispy.tv.ui.edge_to_edge

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.crispy.tv.ui.theme.Dimensions

internal val LocalBottomBarOverlayPadding = staticCompositionLocalOf { 0.dp }

@Composable
fun safeBottomPadding(extra: Dp = 0.dp): Dp {
    val navigationBarsBottom = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()
    return navigationBarsBottom + LocalBottomBarOverlayPadding.current + extra
}

@Composable
fun safeTopPadding(extra: Dp = 0.dp): Dp {
    val statusBarTop = WindowInsets.statusBars
        .asPaddingValues()
        .calculateTopPadding()
    return statusBarTop + extra
}

fun crispyRowHuggingPadding(
    horizontalPadding: Dp,
    vertical: Dp = 0.dp,
): PaddingValues = PaddingValues(
    start = horizontalPadding,
    top = vertical,
    end = horizontalPadding,
    bottom = vertical,
)

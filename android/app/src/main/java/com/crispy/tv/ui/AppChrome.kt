package com.crispy.tv.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val AppBottomBarHeight = 80.dp

val LocalAppChromeInsets = compositionLocalOf<WindowInsets> {
    error("No app chrome insets provided")
}

@Composable
fun rememberAppChromeInsets(
    showTopBar: Boolean,
    showBottomBar: Boolean,
): WindowInsets {
    val chromeInsets = rememberFixedWindowInsets(bottom = if (showBottomBar) AppBottomBarHeight else 0.dp)
    return WindowInsets.systemBars
        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Vertical)
        .add(chromeInsets)
}

@Composable
fun rememberStandaloneTopBarInsets(): WindowInsets {
    return WindowInsets.systemBars
        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Vertical)
}

@Composable
private fun rememberFixedWindowInsets(
    bottom: Dp = 0.dp,
): WindowInsets {
    val density = LocalDensity.current
    return remember(density, bottom) {
        with(density) {
            WindowInsets(
                left = 0,
                top = 0,
                right = 0,
                bottom = bottom.roundToPx(),
            )
        }
    }
}

@Composable
fun rememberInsetPadding(
    windowInsets: WindowInsets,
    horizontal: Dp,
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
): PaddingValues {
    val insetPadding = windowInsets.asPaddingValues()
    return PaddingValues(
        start = horizontal,
        top = insetPadding.calculateTopPadding() + top,
        end = horizontal,
        bottom = insetPadding.calculateBottomPadding() + bottom,
    )
}

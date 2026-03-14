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

val AppTopBarHeight = 64.dp
val AppBottomBarHeight = 80.dp

val LocalAppChromeInsets = compositionLocalOf<WindowInsets> {
    error("No app chrome insets provided")
}

@Composable
fun rememberAppChromeInsets(
    showTopBar: Boolean,
    showBottomBar: Boolean,
): WindowInsets {
    val chromeInsets = rememberFixedWindowInsets(
        top = if (showTopBar) AppTopBarHeight else 0.dp,
        bottom = if (showBottomBar) AppBottomBarHeight else 0.dp,
    )
    return WindowInsets.systemBars
        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Vertical)
        .add(chromeInsets)
}

@Composable
fun rememberStandaloneTopBarInsets(): WindowInsets {
    return WindowInsets.systemBars
        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Vertical)
        .add(rememberFixedWindowInsets(top = AppTopBarHeight))
}

@Composable
private fun rememberFixedWindowInsets(
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
): WindowInsets {
    val density = LocalDensity.current
    return remember(density, top, bottom) {
        with(density) {
            WindowInsets(
                left = 0,
                top = top.roundToPx(),
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

package com.crispy.tv.details

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.lerp

@Composable
internal fun rememberAnimatedColorScheme(
    target: ColorScheme,
    durationMillis: Int = 450,
): ColorScheme {
    val progress = remember { Animatable(1f) }
    var fromScheme by remember { mutableStateOf(target) }
    var toScheme by remember { mutableStateOf(target) }

    val t = progress.value
    val scheme = remember(fromScheme, toScheme, t) { lerpColorScheme(fromScheme, toScheme, t) }

    LaunchedEffect(target) {
        if (target === toScheme) return@LaunchedEffect

        fromScheme = scheme
        toScheme = target
        progress.snapTo(0f)
        progress.animateTo(1f, animationSpec = tween(durationMillis = durationMillis))
    }

    return scheme
}

internal fun lerpColorScheme(a: ColorScheme, b: ColorScheme, t: Float): ColorScheme {
    val clampedT = t.coerceIn(0f, 1f)
    return ColorScheme(
        primary = lerp(a.primary, b.primary, clampedT),
        onPrimary = lerp(a.onPrimary, b.onPrimary, clampedT),
        primaryContainer = lerp(a.primaryContainer, b.primaryContainer, clampedT),
        onPrimaryContainer = lerp(a.onPrimaryContainer, b.onPrimaryContainer, clampedT),
        inversePrimary = lerp(a.inversePrimary, b.inversePrimary, clampedT),
        secondary = lerp(a.secondary, b.secondary, clampedT),
        onSecondary = lerp(a.onSecondary, b.onSecondary, clampedT),
        secondaryContainer = lerp(a.secondaryContainer, b.secondaryContainer, clampedT),
        onSecondaryContainer = lerp(a.onSecondaryContainer, b.onSecondaryContainer, clampedT),
        tertiary = lerp(a.tertiary, b.tertiary, clampedT),
        onTertiary = lerp(a.onTertiary, b.onTertiary, clampedT),
        tertiaryContainer = lerp(a.tertiaryContainer, b.tertiaryContainer, clampedT),
        onTertiaryContainer = lerp(a.onTertiaryContainer, b.onTertiaryContainer, clampedT),
        background = lerp(a.background, b.background, clampedT),
        onBackground = lerp(a.onBackground, b.onBackground, clampedT),
        surface = lerp(a.surface, b.surface, clampedT),
        onSurface = lerp(a.onSurface, b.onSurface, clampedT),
        surfaceVariant = lerp(a.surfaceVariant, b.surfaceVariant, clampedT),
        onSurfaceVariant = lerp(a.onSurfaceVariant, b.onSurfaceVariant, clampedT),
        surfaceTint = lerp(a.surfaceTint, b.surfaceTint, clampedT),
        inverseSurface = lerp(a.inverseSurface, b.inverseSurface, clampedT),
        inverseOnSurface = lerp(a.inverseOnSurface, b.inverseOnSurface, clampedT),
        error = lerp(a.error, b.error, clampedT),
        onError = lerp(a.onError, b.onError, clampedT),
        errorContainer = lerp(a.errorContainer, b.errorContainer, clampedT),
        onErrorContainer = lerp(a.onErrorContainer, b.onErrorContainer, clampedT),
        outline = lerp(a.outline, b.outline, clampedT),
        outlineVariant = lerp(a.outlineVariant, b.outlineVariant, clampedT),
        scrim = lerp(a.scrim, b.scrim, clampedT),
        surfaceBright = lerp(a.surfaceBright, b.surfaceBright, clampedT),
        surfaceDim = lerp(a.surfaceDim, b.surfaceDim, clampedT),
        surfaceContainer = lerp(a.surfaceContainer, b.surfaceContainer, clampedT),
        surfaceContainerHigh = lerp(a.surfaceContainerHigh, b.surfaceContainerHigh, clampedT),
        surfaceContainerHighest = lerp(a.surfaceContainerHighest, b.surfaceContainerHighest, clampedT),
        surfaceContainerLow = lerp(a.surfaceContainerLow, b.surfaceContainerLow, clampedT),
        surfaceContainerLowest = lerp(a.surfaceContainerLowest, b.surfaceContainerLowest, clampedT),
    )
}

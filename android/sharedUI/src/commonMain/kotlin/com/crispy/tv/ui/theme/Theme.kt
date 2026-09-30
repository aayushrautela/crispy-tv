package com.crispy.tv.ui.theme

// Compose Multiplatform 1.11.x ships the `androidx.compose.*` packages, NOT
// `org.jetbrains.compose.*`. JetBrains converged the namespaces: the
// `org.jetbrains.compose.runtime` etc. artifacts are thin aliases that redirect
// to androidx. Verified by unzipping the resolved AARs -- 790 androidx/compose
// classes in runtime, 0 org/jetbrains/compose, and the same for ui, foundation
// and material3. So these imports are shared-identical with the Android app's
// existing design system; only the artifact coordinates differ.
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val CrispyDarkColors = darkColorScheme(
    primary = CrispyPalette.primary,
    onPrimary = CrispyPalette.onPrimary,
    primaryContainer = CrispyPalette.primaryContainer,
    onPrimaryContainer = CrispyPalette.onPrimaryContainer,
    inversePrimary = CrispyPalette.inversePrimary,
    secondary = CrispyPalette.secondary,
    onSecondary = CrispyPalette.onSecondary,
    secondaryContainer = CrispyPalette.secondaryContainer,
    onSecondaryContainer = CrispyPalette.onSecondaryContainer,
    tertiary = CrispyPalette.tertiary,
    onTertiary = CrispyPalette.onTertiary,
    tertiaryContainer = CrispyPalette.tertiaryContainer,
    onTertiaryContainer = CrispyPalette.onTertiaryContainer,
    background = CrispyPalette.background,
    onBackground = CrispyPalette.onBackground,
    surface = CrispyPalette.surface,
    onSurface = CrispyPalette.onSurface,
    surfaceVariant = CrispyPalette.surfaceVariant,
    onSurfaceVariant = CrispyPalette.onSurfaceVariant,
    surfaceContainer = CrispyPalette.surfaceContainer,
    surfaceContainerHigh = CrispyPalette.surfaceContainerHigh,
    surfaceContainerHighest = CrispyPalette.surfaceContainerHighest,
    surfaceContainerLow = CrispyPalette.surfaceContainerLow,
    surfaceContainerLowest = CrispyPalette.surfaceContainerLowest,
    surfaceDim = CrispyPalette.surfaceDim,
    surfaceBright = CrispyPalette.surfaceBright,
    surfaceTint = CrispyPalette.surfaceTint,
    outline = CrispyPalette.outline,
    outlineVariant = CrispyPalette.outlineVariant,
    error = CrispyPalette.error,
    onError = CrispyPalette.onError,
    errorContainer = CrispyPalette.errorContainer,
    onErrorContainer = CrispyPalette.onErrorContainer,
    inverseSurface = CrispyPalette.inverseSurface,
    inverseOnSurface = CrispyPalette.inverseOnSurface,
    scrim = CrispyPalette.scrim,
)

@Composable
fun CrispyRewriteTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CrispyDarkColors, content = content)
}

package com.crispy.tv.ui.theme

// Compose Multiplatform 1.11.x ships the `androidx.compose.*` packages, NOT
// `org.jetbrains.compose.*`. JetBrains converged the namespaces: the
// `org.jetbrains.compose.runtime` etc. artifacts are thin aliases that redirect
// to androidx. Verified by unzipping the resolved AARs -- 790 androidx/compose
// classes in runtime, 0 org/jetbrains/compose, and the same for ui, foundation
// and material3. So these imports are shared-identical with the Android app's
// existing design system; only the artifact coordinates differ.
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
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

/**
 * The phone surface's theme: colours from [CrispyDarkColors] and Google Sans
 * Flex as the app-wide text face.
 *
 * The family is applied slot by slot rather than through a constructor
 * parameter because the ramp's constructor shape is not the same everywhere
 * this module is compiled: the resolved Android artifact
 * (`androidx.compose.material3` 1.5.0-alpha27) exposes a leading-`FontFamily`
 * constructor and a twenty-eight-slot ramp, while the published
 * JetBrains/CMP API -- the one the Apple targets compile against -- documents
 * the fifteen-slot ramp only. The fifteen base slots are the part every
 * version has, so that is what is set here. A surface that keeps its own face
 * pins `fontFamily` explicitly and is unaffected -- the top 10 billboard's
 * Archivo is the standing example.
 */
@Composable
fun CrispyRewriteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CrispyDarkColors,
        typography = with(Typography()) {
            copy(
                displayLarge = displayLarge.copy(fontFamily = GoogleSansFlex),
                displayMedium = displayMedium.copy(fontFamily = GoogleSansFlex),
                displaySmall = displaySmall.copy(fontFamily = GoogleSansFlex),
                headlineLarge = headlineLarge.copy(fontFamily = GoogleSansFlex),
                headlineMedium = headlineMedium.copy(fontFamily = GoogleSansFlex),
                headlineSmall = headlineSmall.copy(fontFamily = GoogleSansFlex),
                titleLarge = titleLarge.copy(fontFamily = GoogleSansFlex),
                titleMedium = titleMedium.copy(fontFamily = GoogleSansFlex),
                titleSmall = titleSmall.copy(fontFamily = GoogleSansFlex),
                bodyLarge = bodyLarge.copy(fontFamily = GoogleSansFlex),
                bodyMedium = bodyMedium.copy(fontFamily = GoogleSansFlex),
                bodySmall = bodySmall.copy(fontFamily = GoogleSansFlex),
                labelLarge = labelLarge.copy(fontFamily = GoogleSansFlex),
                labelMedium = labelMedium.copy(fontFamily = GoogleSansFlex),
                labelSmall = labelSmall.copy(fontFamily = GoogleSansFlex),
            )
        },
        content = content,
    )
}

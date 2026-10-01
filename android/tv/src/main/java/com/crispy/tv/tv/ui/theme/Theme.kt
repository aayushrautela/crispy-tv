package com.crispy.tv.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.crispy.tv.ui.theme.CrispyPalette

/**
 * The 10-foot surface's colour scheme, built from the shared palette.
 *
 * ## Why this file still exists at all
 *
 * It cannot be deleted in favour of `:sharedUI`'s `CrispyRewriteTheme`, because
 * the two use different Material3 libraries: this one is `androidx.tv.material3`
 * and that one is `androidx.compose.material3`. A TV surface is not a phone
 * surface with a larger window; the library difference is what makes them two
 * different themes rather than one theme with two window sizes.
 *
 * What *is* shared is the values, and they were measured identical before this
 * was extracted -- all twenty-seven roles, with `border` and `borderVariant`
 * holding the same two hex values as `outline` and `outlineVariant`. So this is
 * a de-duplication and changes no pixels on either surface.
 *
 * ## The two lines worth reading
 *
 * `border` and `borderVariant` below are the entire mapping between the two
 * libraries' vocabularies. `androidx.tv.material3` names those two roles
 * `border`/`borderVariant`; `androidx.compose.material3` names them
 * `outline`/`outlineVariant`. They are one pair of roles, so the shared palette
 * only carries the `androidx.compose.material3` names -- a file in `commonMain`
 * cannot name `androidx.tv.material3` types at all -- and the TV names are
 * applied here.
 *
 * A missing mapping is a silent colour change, not a compile error, which is
 * why these two lines are written out rather than left implicit.
 *
 * ## What `:tv` cannot express
 *
 * Eight of the palette's 37 roles are not passed here: the seven
 * `surfaceContainer*`/`surfaceDim`/`surfaceBright` roles, which have no slot in
 * `androidx.tv.material3`'s `darkColorScheme`, plus `spinner`, which is not a
 * Material3 role at all. The first seven are defined in the shared palette because
 * the phone surface needs them, not because the 10-foot surface was given an opinion
 * about them; `spinner` is absent because nothing on this surface draws a spinner and
 * `CrispyTvTheme` has no override for it. It used to be listed as the seven, which
 * under-counted the gap by one and left the `spinner` omission looking deliberate
 * when it was only never considered.
 *
 * `CrispyTvDarkColors` is `internal` rather than `private` so that
 * `CrispyTvDarkColorsMappingTest` can read the mapping. That is a real change in
 * reachability and it is the whole reason the mapping is testable: a colour scheme
 * is a plain data class built from `androidx.compose.ui.graphics.Color`, an inline
 * value class over `ULong`, so **constructing it needs no Android runtime** and a
 * plain JVM unit test reaches it. `:tv` being a plain `com.android.application` is
 * therefore not the obstacle it was recorded as being — the `private` modifier was.
 *
 * ## Not here
 *
 * `CrispySpinner` used to be declared in this file under the same name as
 * `:sharedUI`'s. It had **no callers**: all eleven of them imported
 * `com.crispy.tv.ui.theme.CrispySpinner`, so the one in `:tv` was a duplicate
 * that nothing referenced and nothing flagged, because both declarations were
 * `public` and the packages differed. The single definition is now
 * `CrispyPalette.spinner`.
 */
internal val CrispyTvDarkColors = darkColorScheme(
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
    surfaceTint = CrispyPalette.surfaceTint,
    inverseSurface = CrispyPalette.inverseSurface,
    inverseOnSurface = CrispyPalette.inverseOnSurface,
    // The two roles `androidx.tv.material3` calls `border`. See the KDoc above.
    border = CrispyPalette.outline,
    borderVariant = CrispyPalette.outlineVariant,
    error = CrispyPalette.error,
    onError = CrispyPalette.onError,
    errorContainer = CrispyPalette.errorContainer,
    onErrorContainer = CrispyPalette.onErrorContainer,
    scrim = CrispyPalette.scrim,
)

@Composable
fun CrispyTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CrispyTvDarkColors, content = content)
}

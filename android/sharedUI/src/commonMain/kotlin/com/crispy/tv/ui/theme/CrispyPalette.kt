package com.crispy.tv.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Every Crispy colour value, written down exactly once.
 *
 * ## Why this object exists
 *
 * Before it, the same twenty-seven hex literals were written out twice: once in
 * `:android:sharedUI`'s `CrispyRewriteTheme` and once in `:android:tv`'s
 * `CrispyTvTheme`. Nothing joined them, so changing a colour meant finding both
 * copies and hoping you had, and a colour that drifted on one surface would show
 * up as a diff with no explanation.
 *
 * ## The two role names, and why this file uses only one of them
 *
 * `androidx.compose.material3` calls the two border roles `outline` and
 * `outlineVariant`. `androidx.tv.material3` calls the same two roles `border` and
 * `borderVariant`. They are the same roles under different names, and the
 * measured values were identical on both sides before this landed:
 * `0xFF333333` and `0xFF262626` on each.
 *
 * **A difference in role *name* is not a difference in design, and reading two
 * colour schemes side by side makes one look like a product decision that has to
 * be preserved when it is in fact a mapping to be expressed.** The mapping lives
 * on the `:tv` side, because `androidx.tv.material3` is not on a `commonMain`
 * classpath at all, so a file in `commonMain` cannot name `border`. `:tv` writes
 * `border = CrispyPalette.outline`; that line is the whole mapping.
 *
 * `:tv`'s `DetailPalette.kt` maps the same pair by hand at its last four lines
 * (`border = m3.outline`, `borderVariant = m3.outlineVariant`), which is
 * independent confirmation that the two names are the same role rather than a
 * coincidence.
 *
 * ## The seven roles `:tv` cannot express
 *
 * `surfaceContainer` through `surfaceBright` are Material3 Expressive surface
 * roles. `androidx.tv.material3`'s `darkColorScheme` has no slot for them, so
 * they are defined here for the shared layer and simply not passed by `:tv`. They
 * are not missing from the design; the 10-foot surface just has nowhere to read
 * them.
 *
 * ## Not here, deliberately
 *
 * The dynamic per-title seed colour stays out. It is `:tv`'s `DetailPalette.kt`,
 * which depends on `Bitmap`, `Context`, `LruCache`, Coil and `com.materialkolor`,
 * and is therefore Android-only by nature. A palette of constants is what two
 * static surfaces can share; a palette computed from a bitmap is not.
 */
object CrispyPalette {

    /** The one accent colour, used for every spinner and progress indicator. */
    val spinner = Color(0xFFF56E3C)

    val primary = Color(0xFFFFFFFF)
    val onPrimary = Color(0xFF141414)
    val primaryContainer = Color(0xFF2A2A2A)
    val onPrimaryContainer = Color(0xFFFFFFFF)
    val inversePrimary = Color(0xFFFFFFFF)

    val secondary = Color(0xFFB3B3B3)
    val onSecondary = Color(0xFF141414)
    val secondaryContainer = Color(0xFF333333)
    val onSecondaryContainer = Color(0xFFFFFFFF)

    val tertiary = Color(0xFF888888)
    val onTertiary = Color(0xFFFFFFFF)
    val tertiaryContainer = Color(0xFF2A2A2A)
    val onTertiaryContainer = Color(0xFFFFFFFF)

    val background = Color(0xFF141414)
    val onBackground = Color(0xFFFFFFFF)

    val surface = Color(0xFF1F1F1F)
    val onSurface = Color(0xFFFFFFFF)
    val surfaceVariant = Color(0xFF2A2A2A)
    val onSurfaceVariant = Color(0xFFB3B3B3)

    val surfaceContainer = Color(0xFF1F1F1F)
    val surfaceContainerHigh = Color(0xFF2A2A2A)
    val surfaceContainerHighest = Color(0xFF333333)
    val surfaceContainerLow = Color(0xFF141414)
    val surfaceContainerLowest = Color(0xFF0A0A0A)
    val surfaceDim = Color(0xFF0A0A0A)
    val surfaceBright = Color(0xFF2A2A2A)

    val surfaceTint = Color(0xFFFFFFFF)

    /** `androidx.tv.material3` names this role `border`. Same value, other name. */
    val outline = Color(0xFF333333)

    /** `androidx.tv.material3` names this role `borderVariant`. Same value, other name. */
    val outlineVariant = Color(0xFF262626)

    val error = Color(0xFFE8455C)
    val onError = Color(0xFFFFFFFF)
    val errorContainer = Color(0xFFB03040)
    val onErrorContainer = Color(0xFFFFDAD6)

    val inverseSurface = Color(0xFFECE1C6)
    val inverseOnSurface = Color(0xFF141414)

    val scrim = Color(0xFF000000)
}

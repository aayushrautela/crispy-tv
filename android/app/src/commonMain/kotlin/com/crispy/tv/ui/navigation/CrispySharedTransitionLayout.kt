package com.crispy.tv.ui.navigation

import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Provides [LocalSharedTransitionScope] to everything beneath it, on every target.
 *
 * ## Why this is in `commonMain` and its host was not
 *
 * Shared transitions look like a navigation feature, and this file is the proof
 * that they are not. A transition is [SharedTransitionLayout] -- an
 * `androidx.compose.animation` type, i.e. Compose Multiplatform, present on
 * Android, the desktop JVM and both iOS targets. Navigation is what *navigates*;
 * the thing that *transitions* is Compose.
 *
 * So the scope's interface was already in `commonMain`
 * ([LocalSharedTransitionScope], defaulting to `null`, which is why a participant
 * that finds no provider has a defined "no transition" path), and the mechanism is
 * available everywhere. The only Android-bound piece was the two lines that
 * *supplied* the scope, and they lived in `AppNavHost.kt` -- a file that is
 * `androidMain` for its own reasons, because it wraps a
 * `androidx.navigation.compose.NavHost`.
 *
 * This is the same shape as every other seam in this migration: the portable half
 * is a three-line composable, and the platform half is the composition root that
 * calls it.
 *
 * ## What is deliberately not here
 *
 * The seven `*NavGraph.kt` files stay in `androidMain` -- `AuthNavGraph`,
 * `DiscoverNavGraph`, `HomeNavGraph`, `LibraryNavGraph`, `PlayerNavGraph`,
 * `SearchNavGraph` and `SettingsNavGraph`. They declare
 * `NavGraphBuilder` graphs and are genuinely bound to `androidx.navigation`,
 * which is not on the `commonMain` classpath at all -- there is no KMP artifact
 * for it (its only `available-at` files are `-android-` and `*Stubs*`, and the
 * `*Stubs*` variants are dokkadoc stub artifacts, not compilable targets). A
 * desktop shell will need a navigation model that is not this one, and inventing
 * it here would be designing the wrong thing in the wrong place.
 *
 * ## Why `content` has no default
 *
 * A defaulted `content` would let a caller write `CrispySharedTransitionLayout()`
 * and get a provider that provides nothing. The whole point of the slot is that
 * it wraps a subtree, so making it impossible to forget is the correct shape.
 */
@Composable
fun CrispySharedTransitionLayout(content: @Composable () -> Unit) {
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
            content()
        }
    }
}

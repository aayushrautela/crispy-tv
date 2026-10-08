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
 * *supplied* the scope; they lived in `AppNavHost.kt` when that file was still
 * `androidMain`, and they moved with it -- the host is `commonMain` now, and this
 * provider wraps it there.
 *
 * This is the same shape as every other seam in this migration: the portable half
 * is a three-line composable, and the platform half is the composition root that
 * calls it.
 *
 * ## What used to be written here about the graphs, and why it is gone
 *
 * This KDoc used to say the seven `*NavGraph.kt` files stay in `androidMain`
 * because `androidx.navigation` is not on the `commonMain` classpath at all.
 * Both clauses are now false: the graphs are `commonMain` (the JetBrains KMP
 * fork of `navigation-compose` carries them), and `AppNavHost` -- predictive
 * transitions included -- compiles against metadata. The paragraph is replaced
 * rather than amended because a corrected premise with a surviving copy is
 * worse than the original.
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

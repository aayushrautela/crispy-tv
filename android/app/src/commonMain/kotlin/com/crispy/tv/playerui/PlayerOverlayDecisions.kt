package com.crispy.tv.playerui

/**
 * The decisions [PlayerOverlay] makes that do not need the composition.
 *
 * ## Why these are here and not in the composable body
 *
 * `PlayerOverlay` is 429 lines and every one of these was a `val` inside its body, which
 * means **none of them was callable by anything** -- not by a test, not by another composable.
 * Moving the file into `commonMain` did not change that: the file is now portable and still
 * untestable for these, because a `val` inside a `@Composable` needs the composition to run.
 *
 * Each one below is pure over values it already had in hand, so it can be lifted without
 * changing a decision -- the point is to make a decision *nameable*, not to make it different.
 *
 * ## What each one gets wrong when rewritten naively
 *
 * The three differ in exactly the way that makes each one worth a separate case: one has a
 * boundary that looks exclusive and is not, one is a disjunction whose arms need independent
 * cases, and one leans on a nullable chain where a readable rewrite inverts it.
 */

/**
 * The duration to show, preferring the value that has stopped moving.
 *
 * Engines report `durationMs` while loading and it keeps changing afterwards, which makes a
 * progress bar that follows it jitter. [PlayerUiState.stableDurationMs] is set once the
 * duration has settled, so once it is non-zero it wins.
 *
 * **The guard is `> 0L` and not `>= 0L` for a reason a test has to pin**: `stableDurationMs` and
 * `durationMs` are both defaulted to `0L` and `0L` is also the honest answer for "an unknown
 * duration", so zero has to mean "not settled yet" and fall through. A `>=` rewrite would make
 * a never-settled duration of zero hide a real one.
 */
internal fun effectiveDurationMs(stableDurationMs: Long, durationMs: Long): Long =
    if (stableDurationMs > 0L) stableDurationMs else durationMs

/**
 * Whether a player surface is open, counting the stream selector's own visibility.
 *
 * The two sources of truth are independent: [PlayerUiState.activeSurface] is what the overlay
 * opened itself, while `selectorState.visible` is the selector's, and they can disagree in
 * both directions -- a selector can still be on screen after the overlay closed its own
 * surface.
 *
 * **The arms are tested separately and neither alone would catch a rewrite to `&&`.** With
 * `||`, each arm independently answers `true`, so a fixture satisfying only one is a real case
 * rather than decoration; with `&&`, both fixtures answer `false` and the case that would
 * distinguish the two rules does not exist.
 */
internal fun isSurfaceOpen(activeSurface: PlayerSurface, selectorVisible: Boolean): Boolean =
    activeSurface != PlayerSurface.NONE || selectorVisible

/**
 * Whether the details behind the player are a series rather than a film.
 *
 * **The rule is narrow: `true` only when the item type is present and is not `movie`.** So
 * `null` -- whether `details` is null or `itemType` is -- answers `false`, and `movie` in any
 * case answers `false`. Anything else, including a type this codebase does not recognise,
 * answers `true`.
 *
 * **The `== false` at the end is load-bearing, and it is not a stylistic choice.** The
 * expression is "not (this is a movie)" with a nullable chain in the middle, and in Kotlin
 * `null` on either side of `==` against `false` is `false` rather than the `true` that `!`
 * would give. That is the whole reason the null case has to be pinned by a test: it is the
 * one input where the two possible readings of this line disagree.
 *
 * A tidier-looking rewrite -- `itemType != "movie"` -- answers `true` on `null`, because
 * `null != "movie"` is `true`. So the rewrite does not merely simplify the chain, **it flips
 * the answer for an unknown type**, and an unknown type is exactly what the overlay sees while
 * details are still loading. The current answer, `false`, means "not a series", which is the
 * conservative one: a film shows no Episodes surface and no season picker, so guessing wrong
 * here puts controls on screen for a film.
 */
internal fun isSeriesDetails(itemType: String?): Boolean =
    itemType?.equals("movie", ignoreCase = true) == false
package com.crispy.tv.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.accounts.ActiveProfileInfo
import com.crispy.tv.backend.MetadataVideoView
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.details.HeroTrailerLayerArgs
import com.crispy.tv.details.RatingBadgeLogo
import com.crispy.tv.details.RuntimeDetailsEntry
import com.crispy.tv.settings.PlaybackSettingsRepository

/**
 * Everything [addHomeNavGraph] used to build for itself, arriving from outside.
 *
 * [addHomeNavGraph] is a *registration* function: it declares five destinations and
 * nothing else. For most of this migration it read `navController.context` and
 * seventeen `androidMain` factories and helpers inside those five blocks, which is a
 * composition root wearing a graph's clothes. That was the whole of its remaining
 * `androidMain` claim -- the graph names no `android.*` type, exactly like
 * [addSearchNavGraph], [addDiscoverNavGraph], [addLibraryNavGraph] and
 * [addAccountNavGraph] before it.
 *
 * ## Why one bundle rather than twenty-one parameters
 *
 * The other four graphs take three to six. This one needs twenty-one, because it
 * registers five destinations and each of them was reading its own values. A
 * registration function is not a component: nobody constructs one per frame or reads
 * its properties in a layout, so the usual argument for a parameter object -- keeping
 * a constructor call readable -- is weaker here than the argument that *the signature
 * is the thing a reviewer reads*. Twenty-one parameters is a list, and a list that
 * long is one where a swapped pair still compiles.
 *
 * One parameter per crossing, matching the four siblings exactly, is a real option
 * and would cost no extra type. It was not chosen because the siblings show the shape
 * converging: `addSearchNavGraph` took three and `addLibraryNavGraph` already takes
 * seven, with a per-parameter note on why each is remembered by the caller rather
 * than by the graph.
 *
 * ## What is a product and what is a lambda, and why it differs per member
 *
 * The rule elsewhere in this port is that a slot is the *product* when the consumer
 * stores it and a *lambda* when the consumer keys on it:
 *
 *  - [playbackSettingsRepository] is the product. `DetailsRoute` `remember`s it, and a
 *    fresh repository per recomposition would restart the settings collect.
 *  - [loadProfile] is a lambda. `ProfileIconButton` uses it as a
 *    `produceState(initialValue = null, loadProfile, refreshKey)` key, so a fresh
 *    instance each recomposition restarts the profile load.
 *  - [screenWidthDp] and [screenHeightDp] are plain data, because the two thresholds
 *    they feed are decisions and they stay in the graph as [isWideScreenLayout] and
 *    [isCompactWidth]. Those were inline literals inside a composable until this
 *    landing, behind a `LocalConfiguration` read, so they had no name and no test.
 *  - [catalogViewModelFactory], [detailsViewModelFactory] and
 *    [personViewModelFactory] are lambdas that take their varying argument, because
 *    each factory closes over something read from *this graph's* route arguments.
 *    Hoisting them to the caller would make the caller know the section, the item and
 *    the person, which are the graph's business and nobody else's.
 *  - The three route arguments are **not** crossings any more. They used to be, and the
 *    reason they were is stated in [HomeRouteArguments] because it was wrong: it was
 *    written here as "`NavBackStackEntry.arguments` returns `android.os.Bundle` on every
 *    platform", and it returns a common `androidx.savedstate.SavedState?` whose readers
 *    are unreachable — which is a different problem with a different answer. The
 *    `NavBackStackEntry -> SavedStateHandle` step is a one-liner at each of the three
 *    call sites, and the mapping is [catalogRouteArguments], [detailsRouteArguments] and
 *    [personRouteArguments]. **So the statement "that is why the four graphs that moved
 *    before this one moved: none of them registers a route argument" no longer explains
 *    anything** — this graph's remaining `androidMain` claims are the ones its factories
 *    make, not its arguments'.
 *
 * Every member is required. A defaulted crossing here would be a value the Android
 * composition root could silently forget, and the file this replaces had five places
 * where that mistake would have been invisible.
 */
internal class HomeNavDependencies(
    val homeViewModelFactory: ViewModelProvider.Factory,
    val homeSelectorViewModelFactory: ViewModelProvider.Factory,
    val loadProfile: suspend () -> ActiveProfileInfo?,
    val calendarViewModelFactory: ViewModelProvider.Factory,
    val catalogViewModelFactory: (section: CatalogSectionRef) -> ViewModelProvider.Factory,
    val detailsViewModelFactory: (itemId: String, itemType: String, runtimeEntry: RuntimeDetailsEntry?) -> ViewModelProvider.Factory,
    val personViewModelFactory: (personId: String) -> ViewModelProvider.Factory,
    
    val playbackSettingsRepository: PlaybackSettingsRepository,
    val shareText: (text: String) -> Unit,
    val dateFormat: (epochMillis: Long) -> String,
    val timeFormat: (epochMillis: Long) -> String,
    val clock: () -> Long,
    val screenWidthDp: Int,
    val screenHeightDp: Int,
    val youtubeTrailerPlaybackSupported: Boolean,
    val imageSeedColor: @Composable (imageUrl: String?, fallbackSeed: Color) -> Color?,
    val heroTrailerLayer: @Composable (HeroTrailerLayerArgs) -> Unit,
    val reviewProviderBadge: @Composable (provider: String) -> Unit,
    val ratingBadgeLogo: @Composable (logo: RatingBadgeLogo) -> Unit,
    val youTubeExtraVideoDialog: @Composable (video: MetadataVideoView?, onDismiss: () -> Unit) -> Unit,
    val formatBirthday: (epochMillis: Long) -> String,
)

/**
 * The details screen's wide threshold, kept as a rule rather than as an argument.
 *
 * `screenWidthDp >= 768 && screenHeightDp < screenWidthDp` was written inline inside
 * the details block of [addHomeNavGraph], behind a `LocalConfiguration` read, so it
 * had no name and no test. The second clause is not redundancy: it is what stops a
 * tablet held in portrait -- 800dp wide and 1200dp tall -- from reading as wide,
 * which is the answer the first clause alone would give.
 */
internal fun isWideScreenLayout(screenWidthDp: Int, screenHeightDp: Int): Boolean =
    screenWidthDp >= 768 && screenHeightDp < screenWidthDp

/**
 * The 600dp compact threshold, shared by the home screen's stream selector and the
 * details screen.
 *
 * Two destinations read it and they read it *separately*, which is deliberate and
 * predates this landing: they are two decisions that happen to share one number, and
 * the home block's comment says so. Naming the rule is what makes the sharing visible
 * without collapsing the two calls into one, which would make the second decision
 * unreviewable.
 */
internal fun isCompactWidth(screenWidthDp: Int): Boolean = screenWidthDp < 600

/**
 * The details destination's route arguments, exactly as the platform handed them over.
 *
 * Every field is nullable and **nothing has been decided**. A blank string is still
 * `""`, an unparseable number is still `"abc"`, and whether either means anything is a
 * question [addHomeNavGraph] asks because that is where the question was asked before.
 * Keeping the decisions on this side is the point: a test in `commonTest` can reach
 * them, and it can now reach the mapping too -- see [detailsRouteArguments].
 *
 * See [runtimeDetailsEntryOrNull] for the one of those decisions with a name.
 *
 * `internal` now, and it was `public` before the readers moved: a public class cannot
 * expose an internal member type, and `AppNavHostDependencies`' three
 * `(NavBackStackEntry) -> …RouteArgs` members were the only public API naming these three
 * types. With them deleted, the widest thing that names them is [addHomeNavGraph] and
 * [detailsRouteArguments], both `internal`, so the reason for `public` is gone. That is
 * the rule worth keeping: **a type's visibility is set by the widest signature that names
 * it, not by what the type itself needs** — so it is a measurement, and it changed here.
 */
internal data class HomeDetailsRouteArgs(
    val itemId: String?,
    val itemType: String?,
    val highlightEpisodeId: String?,
    val autoOpenEpisode: Boolean,
    val runtimeSeasonNumber: String?,
    val runtimeEpisodeNumber: String?,
    val runtimeAbsoluteEpisodeNumber: String?,
    val initialArtworkUrl: String?,
    val sharedElementKey: String?,
)

/**
 * The person destination's route arguments, with nothing decided. See
 * [HomeDetailsRouteArgs] for why the fields are nullable, why they are unparsed, and
 * why they are `internal`.
 */
internal data class HomePersonRouteArgs(
    val personId: String?,
    val profileUrl: String?,
)

/**
 * The catalog destination's route arguments, with nothing decided. See
 * [HomeDetailsRouteArgs] for why the fields are nullable, why they are unparsed, and
 * why they are `internal`.
 */
internal data class HomeCatalogRouteArgs(
    val catalogId: String?,
    val title: String?,
)

/**
 * A runtime entry exists only when at least one of its three numbers was reported.
 *
 * All three arrive as raw strings, so each is parsed here and the whole entry is
 * dropped when none of them parsed. That was a seven-line `takeIf` written inline
 * inside a composable, with no name and no test, and it is the reason the argument
 * reader hands over strings rather than an entry: **this rule is worth testing and it
 * cannot live on the platform side.**
 *
 * An entry carrying three nulls is not the same answer as no entry -- the screen
 * branches on it -- so the distinction has to be carried by the value rather than by a
 * sentinel number such as `-1`.
 */
internal fun runtimeDetailsEntryOrNull(
    seasonNumber: String?,
    episodeNumber: String?,
    absoluteEpisodeNumber: String?,
): RuntimeDetailsEntry? =
    RuntimeDetailsEntry(
        seasonNumber = seasonNumber?.toIntOrNull(),
        episodeNumber = episodeNumber?.toIntOrNull(),
        absoluteEpisodeNumber = absoluteEpisodeNumber?.toIntOrNull(),
    ).takeIf {
        it.seasonNumber != null || it.episodeNumber != null || it.absoluteEpisodeNumber != null
    }

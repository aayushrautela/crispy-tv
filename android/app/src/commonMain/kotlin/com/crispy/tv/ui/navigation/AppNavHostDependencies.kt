package com.crispy.tv.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import com.crispy.tv.accounts.ActiveProfileInfo
import com.crispy.tv.backend.MetadataVideoView
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.details.HeroTrailerLayerArgs
import com.crispy.tv.details.RatingBadgeLogo
import com.crispy.tv.details.RuntimeDetailsEntry
import com.crispy.tv.platform.AppLogger
import com.crispy.tv.settings.ImageSettingsRepository
import com.crispy.tv.settings.PlaybackSettingsRepository
import com.crispy.tv.sync.ProfileDataCloudSync

/**
 * Everything [AppNavHost] needs from a platform, in one bundle.
 *
 * ## Why one flat bundle and not six per-graph bundles
 *
 * The graphs already take their own bundles (`HomeNavDependencies`,
 * `SettingsNavDependencies`), and those are unchanged. The question was whether *this*
 * class should carry six more bundles and let the Android side assemble them, or carry
 * the flat products and let `AppNavHost` assemble.
 *
 * It carries the flat products, for a reason that is specific to what this class is:
 * **`AppNavHost` is a registration function, not a component.** Nobody builds one per
 * frame, so the usual argument for a bundle -- "it keeps a constructor call readable" --
 * is weak here, and the stronger argument is that the flat form keeps the *decisions* in
 * the shared file. Assembly is not wiring: `HomeNavDependencies(personViewModelFactory =
 * d.homePersonViewModelFactory, …)` decides nothing, and on this side of the line it is
 * readable and it is testable, whereas the same thirty lines in an `androidMain` file
 * would be neither. The
 * `SettingsNavDependencies` construction keeps its `playbackSettingsRepository =
 * d.homePlaybackSettingsRepository` aliasing here for the same reason: that alias is a
 * decision about *one* repository serving two graphs, and it is worth being able to see.
 *
 * ## What crossed, and what each crossing is
 *
 * | Crossing | Why it is a product and not a lambda |
 * |---|---|
 * | `screenWidthDp` / `screenHeightDp` as two `Int`s | `androidx.compose.ui.platform.LocalConfiguration` is Android-only and has no `commonMain` counterpart by that name, and its only two uses were `.screenWidthDp` and `.screenHeightDp`. A slot over the composition local would have made the shared file read a configuration it cannot hold. |
 * | `addPlayerDestination: (builder: NavGraphBuilder, navController: NavHostController) -> Unit` | `PlayerNavGraph.kt` registers 16 `navArgument(` declarations and reads four of them off `entry.arguments`, whose readers are unreachable outside `androidx.savedstate` (see `HomeRouteArguments`), so it stays in `androidMain`. A registration function crossing as a function is the same shape `pluginsSettingsScreen` already uses in `SettingsNavDependencies`. |
 * | The four `@Composable` slots | Media3's trailer surface, the badge composables and the YouTube dialog are Android by the user's directive. They were already `@Composable` slots on `HomeNavDependencies`; this class carries them unchanged. |
 * | `pluginsUiSupported` as a `Boolean` | `DistributionCapabilities` is already `commonMain` (`:platform-core`), so only the *read* of it needed crossing. |
 * | ~~The three `(NavBackStackEntry) -> …RouteArgs` readers~~ **gone** | They were here because this file's KDoc claimed `NavBackStackEntry.arguments` returns `android.os.Bundle` on every platform. It does not — it is a common `SavedState?` whose readers are `@PublishedApi internal` to `androidx.savedstate` — and `savedStateHandle` (already used in four shared graphs) carries the same arguments. So the readers are now `detailsRouteArguments` and friends over a `SavedStateHandle`, in `HomeRouteArguments.kt`. |
 *
 * ## The five `activeProfileLoader` instances
 *
 * Five members of this class are `suspend () -> ActiveProfileInfo?` and **all five are
 * separately built on the Android side, on purpose.** `ProfileIconButton` keys a
 * `produceState` on the loader's identity, so one shared instance would let a
 * recomposition on any tab restart a load another tab is waiting on. Each graph built
 * its own loader inside its own composable body before any of this was shared, and five
 * remembered values is the behaviour-preserving shape. The identical argument does *not*
 * apply to `homePlaybackSettingsRepository`, which is deliberately aliased into the
 * settings bundle: a repository is a store rather than a callback, and two stores for
 * one app context would be two sources of truth for the same setting.
 *
 * ## `remember`ed on the Android side, deliberately
 *
 * Every member is built by a `@Composable` producer per platform, because
 * `LocalConfiguration` and `LocalPlatformContext` are composition locals. That is also
 * where all the `remember`s are, and the two that are *not* remembered there are
 * deliberate: `screenWidthDp`/`screenHeightDp` are re-read on every composition so a
 * configuration change is visible on the next one, and the four `@Composable` slots are
 * rebuilt every composition because that is what the graphs used to do -- they capture
 * nothing, so a stable identity would buy nothing and a stale one would be a bug.
 *
 * ## Flat, and assembled per platform
 *
 * A flat bundle rather than a builder because `AppNavHost` is a registration function, not
 * a component: the decisions about what to show stay in the shared file, and only the
 * *assembly* of the products belongs to the platform. It is `public` rather than `internal`
 * for the same reason `AppRoot` is -- `:desktopApp` is a separate module from `:app` and
 * has to be able to build one -- and an `internal` class here would make the shared shell
 * unreachable off Android even though every member type is common.
 */
class AppNavHostDependencies(
    // ---- search -----------------------------------------------------------------
    val searchViewModelFactory: ViewModelProvider.Factory,
    val searchLoadProfile: suspend () -> ActiveProfileInfo?,
    // ---- random wheel -----------------------------------------------------------
    // The wheel reads the same snapshot the home screen does, so its factory takes the
    // same `HomeCatalogService` instance as everything else in this bundle.
    val randomWheelViewModelFactory: ViewModelProvider.Factory,
    // ---- account ----------------------------------------------------------------
    val profileListFactory: ViewModelProvider.Factory,
    val accountSettingsFactory: ViewModelProvider.Factory,
    val accountLoadProfile: suspend () -> ActiveProfileInfo?,
    // ---- library ----------------------------------------------------------------
    val libraryViewModelFactory: ViewModelProvider.Factory,
    val libraryMonthName: (monthKey: String) -> String,
    val libraryClock: () -> Long,
    val libraryUtcOffsetMillis: () -> Long,
    val libraryLoadProfile: suspend () -> ActiveProfileInfo?,
    val libraryLogger: AppLogger,
    // ---- discover ---------------------------------------------------------------
    val discoverViewModelFactory: ViewModelProvider.Factory,
    val discoverLoadProfile: suspend () -> ActiveProfileInfo?,
    // ---- home -------------------------------------------------------------------
    val homeViewModelFactory: ViewModelProvider.Factory,
    val homeSelectorViewModelFactory: ViewModelProvider.Factory,
    val homeLoadProfile: suspend () -> ActiveProfileInfo?,
    val homeCalendarViewModelFactory: ViewModelProvider.Factory,
    val homeCatalogViewModelFactory: (section: CatalogSectionRef) -> ViewModelProvider.Factory,
    val homeDetailsViewModelFactory: (
        itemId: String,
        itemType: String,
        runtimeEntry: RuntimeDetailsEntry?,
    ) -> ViewModelProvider.Factory,
    val homePersonViewModelFactory: (personId: String) -> ViewModelProvider.Factory,
    val homePlaybackSettingsRepository: PlaybackSettingsRepository,
    val homeShareText: (text: String) -> Unit,
    val homeDateFormat: (epochMillis: Long) -> String,
    val homeTimeFormat: (epochMillis: Long) -> String,
    val homeClock: () -> Long,
    val homeScreenWidthDp: Int,
    val homeScreenHeightDp: Int,
    val homeYoutubeTrailerPlaybackSupported: Boolean,
    val homeImageSeedColor: @Composable (imageUrl: String?, fallbackSeed: Color) -> Color?,
    val homeHeroTrailerLayer: @Composable (args: HeroTrailerLayerArgs) -> Unit,
    val homeReviewProviderBadge: @Composable (provider: String) -> Unit,
    val homeRatingBadgeLogo: @Composable (logo: RatingBadgeLogo) -> Unit,
    val homeYouTubeExtraVideoDialog: @Composable (video: MetadataVideoView?, onDismiss: () -> Unit) -> Unit,
    val homeFormatBirthday: (epochMillis: Long) -> String,
    // ---- settings ---------------------------------------------------------------
    val pluginsUiSupported: Boolean,
    val pluginsSettingsScreen: (@Composable (onBack: () -> Unit) -> Unit)?,
    val addonsSettingsViewModelFactory: ViewModelProvider.Factory,
    val imageSettingsRepository: ImageSettingsRepository,
    val profileDataCloudSync: ProfileDataCloudSync,
    // ---- player -----------------------------------------------------------------
    val addPlayerDestination: (builder: NavGraphBuilder, navController: NavHostController) -> Unit,
)
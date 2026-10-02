@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.crispy.tv.details

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.crispy.tv.addons.model.MediaDetails
import com.crispy.tv.addons.model.MediaVideo
import com.crispy.tv.addons.streams.AddonStream
import com.crispy.tv.addons.streams.StreamSelectorUiState
import com.crispy.tv.backend.MetadataReviewView
import com.crispy.tv.backend.MetadataVideoView
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.details.trailer.TrailerSource
import com.crispy.tv.details.trailer.classifyTrailerSource
import com.crispy.tv.details.trailer.extractYouTubeVideoId
import com.crispy.tv.settings.PlaybackSettings
import com.crispy.tv.streams.StreamSelectorSheet
import com.crispy.tv.ui.components.CrispyIcon
import com.crispy.tv.ui.components.ItemActionSheet
import com.crispy.tv.ui.components.ItemActionSheetItem
import com.crispy.tv.ui.edge_to_edge.safeBottomPadding
import com.crispy.tv.ui.navigation.LocalNavAnimatedContentScope
import com.crispy.tv.ui.navigation.animateContentAlpha
import com.crispy.tv.ui.resources.Res
import com.crispy.tv.ui.resources.ic_arrow_back_filled
import com.crispy.tv.ui.resources.ic_check
import com.crispy.tv.ui.resources.ic_check_filled
import com.crispy.tv.ui.resources.ic_done_all
import com.crispy.tv.ui.resources.ic_done_all_filled
import com.crispy.tv.ui.resources.ic_star_filled
import com.crispy.tv.ui.resources.ic_volume_off_filled
import com.crispy.tv.ui.resources.ic_volume_up_filled
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource

private val HERO_TRAILER_STOP_SCROLL_THRESHOLD = 120.dp

@Composable
internal fun DetailsScreen(
    uiState: DetailsUiState,
    selectorState: StreamSelectorUiState,
    selectorDetails: MediaDetails?,
    selectorHeaderEpisode: MediaVideo?,
    playbackSettings: PlaybackSettings,
    initialArtworkUrl: String? = null,
    sharedElementKey: String? = null,
    onBack: () -> Unit,
    onItemClick: (CatalogItem, String?) -> Unit,
    onPersonClick: (personId: String, profileUrl: String?) -> Unit,
    onRetry: () -> Unit,
    onSeasonSelected: (Int) -> Unit,
    onOpenStreamSelector: () -> Unit,
    onEpisodeClick: (String) -> Unit,
    onToggleEpisodeWatched: (MediaVideo) -> Unit,
    onToggleSeasonWatched: (String, Int) -> Unit,
    onDismissStreamSelector: () -> Unit,
    onProviderSelected: (String?) -> Unit,
    onStreamSelected: (AddonStream) -> Unit,
    onToggleWatchlist: () -> Unit,
    onToggleWatched: () -> Unit,
    onSetLiked: (Boolean?) -> Unit,
    onTrailerMutedChanged: (Boolean) -> Unit,
    onAiInsightsClick: () -> Unit,
    onDismissAiInsights: () -> Unit,
    /**
     * Sharing, as the step rather than as a string. The screen composes the text
     * ("Check out <title> on Crispy") and hands it over; it does not build an
     * `ACTION_SEND` chooser.
     *
     * **It used to, twice.** The `onShare` branch of `AiInsightsStoryOverlay`
     * hand-built `Intent.ACTION_SEND` + `Intent.createChooser` inline while the
     * `shareText` slot two hundred lines up already called `shareOnCrispy`. The
     * hand-built one omitted `FLAG_ACTIVITY_NEW_TASK`, which
     * `LocaleDateFormatters.shareOnCrispy` documents as required because
     * `LocalContext.current` is not necessarily the application context -- so this
     * was a latent `AndroidRuntimeException` on the one path a user reaches by
     * tapping share on an AI insights card, not merely a duplicate.
     */
    shareText: (String) -> Unit,
    /**
     * The two formatters cross as **function values, not as the `LocaleDateFormatters`
     * class**: the class is declared in `androidMain`, so putting it in a
     * `commonMain` signature would re-create the pin this landing exists to remove.
     * `DetailsHeader` already takes them in this shape, and it is the reason this
     * is the second occurrence rather than a new pattern.
     */
    dateFormat: (Long) -> String,
    timeFormat: (Long) -> String,
    /**
     * `System.currentTimeMillis()` needs no import, so no scan can see it. It
     * crossed because the screen only *builds* the slot its callee already had;
     * `DetailsHeader` reads `clock` to compute a countdown, which is exactly the
     * kind of value a test has to be able to supply.
     */
    clock: () -> Long,
    /**
     * Two booleans, not one, and not a screen-size pair. `LocalConfiguration` is
     * absent from Compose Multiplatform's common metadata and lives in
     * `ui-android`'s `AndroidCompositionLocals_androidKt`, so no `commonMain`
     * composable can read it. The 768 and 600 thresholds are separate decisions
     * that happen to share one read, so they stay separate here.
     */
    isWideScreen: Boolean,
    isCompact: Boolean,
    /**
     * `AppDistribution.current.capabilities.youtubeInHeroPlaybackSupported`,
     * read twice. The seam is Android-only because Android's composition root is
     * `CrispyApplication`; what crosses is the one boolean both reads wanted, and
     * the decision built from it -- see `trailerNeedsEmbedFallback` below.
     */
    youtubeTrailerPlaybackSupported: Boolean,
    /**
     * The window's height in dp, because `heroHeight` is a pure function and
     * `LocalConfiguration` cannot be read from `commonMain`. Crossing the value
     * rather than the reader is what keeps the 40% rule testable here instead of
     * moving it to the caller, where it would be untestable and unowned.
     */
    screenHeightDp: Int,
    /**
     * The Media3 trailer surface, as a slot. `HeroTrailerLayer` is an ExoPlayer
     * plus an inflated `PlayerView` plus libass, so it has no portable form and
     * stays in `androidMain` -- exactly the split `PlaybackSurfaceController`
     * made against `PlaybackSessionController`.
     */
    heroTrailerLayer: @Composable (HeroTrailerLayerArgs) -> Unit,
    /**
     * The artwork's seed colour, sampled on a background thread.
     *
     * `rememberSeedColor` is `@Composable` and reaches Coil's
     * `PlatformContext` and `LocalContext`, and it returns `State<Color?>`
     * because the sample arrives asynchronously -- so the *value* crosses as a
     * composable slot returning `Color?`, and the `?: fallbackSeed` decision
     * stays here, where it was before.
     */
    imageSeedColor: @Composable (imageUrl: String?, fallbackSeed: Color) -> Color?,
    /**
     * Three composable slots for the three things this screen used to *call*
     * directly, each of which stays in `androidMain` for a different and stated
     * reason: `ReviewProviderBadge` and `DetailsRatingBadgeLogo` render `R.raw.*`
     * assets from `:ui-assets`, a plain `com.android.library` with no JVM variant
     * (and CMP documents SVG as unsupported on Android, so the assets cannot even
     * become `composeResources`), while `YouTubeExtraVideoDialog` is a dialog and
     * has no portable form.
     *
     * `DetailsBody` already takes the first two in exactly this shape with no
     * default -- this screen was bypassing its own callee's slots to call the
     * Android implementations itself.
     *
     * The two `YouTubeExtraVideoDialog` calls are **not** merged. They differ in
     * which of `selectedMakingOfVideo` / `selectedTrailerEmbed` is non-null, and
     * today BOTH compose because the dialog early-returns on a null video; each
     * still acquires audio focus and registers a playback source. Collapsing them
     * would change behaviour that nothing here is being measured against.
     */
    reviewProviderBadge: @Composable (String) -> Unit,
    ratingBadgeLogo: @Composable (RatingBadgeLogo) -> Unit,
    youTubeExtraVideoDialog: @Composable (MetadataVideoView?, () -> Unit) -> Unit,
) {
    val details = uiState.details
    val aiBackdropUrls =
        remember(details) {
            buildList {
                details?.artworkUrl?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            }.distinct()
        }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageUrl = remember(details, initialArtworkUrl) {
        detailsHeroImageUrl(details = details) ?: initialArtworkUrl
    }
    val fallbackSeed = Color.White
    // Both values, not one: `showPalettePlaceholder` below distinguishes "no seed"
    // from "the seed is white", and folding the null away here would erase that.
    val rawSeed = imageSeedColor(imageUrl, fallbackSeed)
    val seedColor = rawSeed ?: fallbackSeed
    val detailsScheme = rememberDetailsColorScheme(seedColor = seedColor)
    val detailsSchemeAnimated = rememberAnimatedColorScheme(target = detailsScheme)
    val palette = remember(detailsSchemeAnimated) { detailsPaletteFromScheme(detailsSchemeAnimated) }
    val showPalettePlaceholder = details == null || rawSeed == null

    var isScreenResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, _ ->
                isScreenResumed =
                    lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val visibleDetails = if (showPalettePlaceholder) null else details
    val visibleUiState = if (showPalettePlaceholder) uiState.copy(details = null, isLoading = true) else uiState

    var entrancePlayed by rememberSaveable { mutableStateOf(false) }
    val entranceAlpha = remember { Animatable(if (entrancePlayed) 1f else 0f) }
    LaunchedEffect(visibleDetails != null) {
        if (visibleDetails == null || entrancePlayed) return@LaunchedEffect
        entranceAlpha.animateTo(1f, tween(durationMillis = 420, easing = FastOutSlowInEasing))
        entrancePlayed = true
    }
    val softFade: Modifier = if (entrancePlayed) {
        Modifier
    } else {
        Modifier.graphicsLayer(alpha = entranceAlpha.value)
    }

    val heroTrailerSources = remember(uiState.titleDetail) {
        val remote = uiState.titleDetail?.item?.trailerUrl
        if (!remote.isNullOrBlank()) {
            listOf(HeroTrailerSource(id = remote, source = classifyTrailerSource(remote)))
        } else {
            val videos = uiState.titleDetail?.videos
            val yt = videos?.firstOrNull { it.key.isNotBlank() && it.official && it.type.equals("Trailer", true) }
                ?: videos?.firstOrNull { it.key.isNotBlank() && it.type.equals("Trailer", true) }
                ?: videos?.firstOrNull { it.key.isNotBlank() }
            yt?.key?.trim()?.takeIf { it.isNotBlank() }
                ?.let { listOf(HeroTrailerSource(id = it, source = TrailerSource.YOUTUBE)) }
                .orEmpty()
        }
    }

    val trailerKey = heroTrailerSources.firstOrNull()?.id

    val trailerStopScrollThresholdPx = remember(density) {
        with(density) { HERO_TRAILER_STOP_SCROLL_THRESHOLD.roundToPx() }
    }

    val heroAllowsTrailerPlayback by remember(listState, trailerStopScrollThresholdPx) {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset <= trailerStopScrollThresholdPx
        }
    }

    var showTrailer by rememberSaveable(trailerKey) { mutableStateOf(false) }
    var userPausedTrailer by rememberSaveable(trailerKey) { mutableStateOf(false) }
    val userMutedTrailer = playbackSettings.trailerMuted

    LaunchedEffect(trailerKey, playbackSettings.trailerAutoplayEnabled) {
        showTrailer = false
        userPausedTrailer = false

        if (trailerKey.isNullOrBlank()) return@LaunchedEffect
        if (!playbackSettings.trailerAutoplayEnabled) return@LaunchedEffect
        if (trailerNeedsEmbedFallback(youtubeTrailerPlaybackSupported, heroTrailerSources.firstOrNull()?.source)) {
            return@LaunchedEffect
        }

        delay(2000)
        showTrailer = true
    }

    val trailerPlaybackBlocked =
        selectorState.visible || visibleUiState.aiStoryVisible || !isScreenResumed

    val isTrailerPlaying =
        showTrailer &&
            heroAllowsTrailerPlayback &&
            !userPausedTrailer &&
            !trailerPlaybackBlocked

    val topBarAlpha by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) {
                1f
            } else {
                (listState.firstVisibleItemScrollOffset / 420f).coerceIn(0f, 1f)
            }
        }
    }

    val containerColor = palette.pageBackground.copy(alpha = topBarAlpha)
    val contentColor = lerp(Color.White, palette.onPageBackground, topBarAlpha)

    var selectedMakingOfVideo by remember { mutableStateOf<MetadataVideoView?>(null) }
    var selectedTrailerEmbed by remember { mutableStateOf<MetadataVideoView?>(null) }
    var expandedReview by remember { mutableStateOf<MetadataReviewView?>(null) }
    var selectedEpisodeAction by remember { mutableStateOf<MediaVideo?>(null) }
    val reviewSheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val episodeSheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val bodyHorizontalPadding = responsivePageHorizontalPadding()

    MaterialTheme(colorScheme = detailsScheme) {
        val animatedVisibilityScope = LocalNavAnimatedContentScope.current
        val contentAlpha = animatedVisibilityScope?.let { scope ->
            with(scope) { animateContentAlpha() }
        } ?: 1f
        Box(modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(alpha = contentAlpha)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .navigationBarsPadding(),
                state = listState,
                contentPadding = PaddingValues(bottom = safeBottomPadding()),
            ) {
                item(key = "hero") {
                    HeroSection(
                        details = visibleDetails,
                        imageUrl = imageUrl,
                        palette = palette,
                        trailer = heroTrailerSources,
                        screenHeightDp = screenHeightDp,
                        heroTrailerLayer = heroTrailerLayer,
                        showTrailer = showTrailer,
                        isTrailerPlaying = isTrailerPlaying,
                        isTrailerMuted = userMutedTrailer,
                        onHeroImageLoaded = {},
                        onHeroImageLoadFailed = {},
                        onToggleTrailer = {
                            if (!trailerKey.isNullOrBlank()) {
                                val primary = heroTrailerSources.firstOrNull()
                                if (trailerNeedsEmbedFallback(youtubeTrailerPlaybackSupported, primary?.source)) {
                                    // `?.` not `.`: the guard above guarantees non-null, but it
                                    // guarantees it to a *call*, not to the data-flow analysis, and
                                    // the extraction is what turned an inline comparison into one.
                                    selectedTrailerEmbed = primary?.toEmbeddedVideo()
                                } else if (!showTrailer) {
                                    showTrailer = true
                                    userPausedTrailer = false
                                } else {
                                    userPausedTrailer = !userPausedTrailer
                                }
                            }
                        },
                        onFocusLossPause = { userPausedTrailer = true },
                        itemId = uiState.itemId,
                        sharedElementKey = sharedElementKey,
                        softFade = softFade,
                    )
                }

                item(key = "header") {
                    HeaderInfoSection(
                        details = visibleDetails,
                        isInWatchlist = visibleUiState.isInWatchlist,
                        isWatched = visibleUiState.isWatched || visibleUiState.isShowFullyWatched,
                        liked = visibleUiState.liked,
                        optimisticSync = visibleUiState.optimisticSync,
                        palette = palette,
                        watchCta = visibleUiState.watchCta,
                        aiInsightsIsLoading = visibleUiState.aiIsLoading,
                        onAiInsightsClick = onAiInsightsClick,
                        onWatchNow = onOpenStreamSelector,
                        onToggleWatchlist = onToggleWatchlist,
                        onToggleWatched = onToggleWatched,
                        onSetLiked = onSetLiked,
                        shareText = shareText,
                        dateFormat = dateFormat,
                        timeFormat = timeFormat,
                        clock = clock,
                        isWideScreen = isWideScreen,
                        softFade = softFade,
                    )
                }

                detailsBodyContent(
                    uiState = visibleUiState,
                    horizontalPadding = bodyHorizontalPadding,
                    palette = palette,
                    onRetry = onRetry,
                    onSeasonSelected = onSeasonSelected,
                    onItemClick = onItemClick,
                    reviewProviderBadge = reviewProviderBadge,
                    ratingBadgeLogo = ratingBadgeLogo,
                    onPersonClick = onPersonClick,
                    onEpisodeClick = onEpisodeClick,
                    onToggleEpisodeWatched = onToggleEpisodeWatched,
                    onMakingOfVideoClick = { selectedMakingOfVideo = it },
                    onReviewClick = { expandedReview = it },
                    onEpisodeLongPress = { selectedEpisodeAction = it },
                )
            }

            TopAppBar(
                windowInsets = TopAppBarDefaults.windowInsets,
                title = {
                    Text(
                        text = if (topBarAlpha > 0.65f) visibleDetails?.title ?: "Details" else "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = contentColor
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        CrispyIcon(
                            painter = painterResource(Res.drawable.ic_arrow_back_filled),
                            contentDescription = "Back",
                            tint = contentColor,
                            autoMirror = true,
                        )
                    }
                },
                actions = {
                    if (showTrailer && !trailerKey.isNullOrBlank()) {
                        IconButton(onClick = { onTrailerMutedChanged(!userMutedTrailer) }) {
                            CrispyIcon(
                                painter = painterResource(if (userMutedTrailer) Res.drawable.ic_volume_off_filled else Res.drawable.ic_volume_up_filled),
                                contentDescription = if (userMutedTrailer) "Unmute trailer" else "Mute trailer",
                                tint = contentColor,
                                autoMirror = true,
                            )
                        }
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = containerColor,
                        titleContentColor = contentColor,
                        navigationIconContentColor = contentColor,
                        actionIconContentColor = contentColor
                    )
            )

            youTubeExtraVideoDialog(selectedMakingOfVideo) { selectedMakingOfVideo = null }

            youTubeExtraVideoDialog(selectedTrailerEmbed) { selectedTrailerEmbed = null }

            StreamSelectorSheet(
                visible = selectorState.visible,
                state = selectorState,
                details = selectorDetails,
                headerEpisode = selectorHeaderEpisode,
                accentColor = palette.accent,
                onAccentColor = palette.onAccent,
                // The sheet asks its own question (`screenWidthDp < 600`) and this
                // screen asks its own (`>= 768` and landscape). They were two reads
                // of one `LocalConfiguration`; they are now two parameters, still
                // not one name, because the thresholds are separate decisions that
                // happened to share a read.
                isCompact = isCompact,
                onDismiss = onDismissStreamSelector,
                onProviderSelected = onProviderSelected,
                onStreamSelected = onStreamSelected,
            )

            if (expandedReview != null) {
                val review = expandedReview!!
                ModalBottomSheet(
                    onDismissRequest = { expandedReview = null },
                    sheetState = reviewSheetState,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(top = 6.dp, bottom = 18.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    review.author?.takeIf { it.isNotBlank() }
                                        ?: review.username?.takeIf { it.isNotBlank() }
                                        ?: "Review",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                review.rating?.let {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Icon(
                                            painter = painterResource(Res.drawable.ic_star_filled),
                                            contentDescription = null,
                                            tint = Color(0xFFFFD54F),
                                        )
                                        Text("${it.toInt()}/10", style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                            reviewProviderBadge(review.provider)
                        }

                        Text(review.content.trim(), style = MaterialTheme.typography.bodyMedium)

                        TextButton(
                            onClick = { expandedReview = null },
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text("Close")
                        }
                    }
                }
            }

            if (selectedEpisodeAction != null) {
                val selectedEpisode = selectedEpisodeAction!!
                val watchState = visibleUiState.episodeWatchStates[selectedEpisode.id] ?: EpisodeWatchState()
                val showTitle = visibleDetails?.title ?: details?.title
                val episodeSeason = selectedEpisode.season
                val seasonItemId = episodeSeason?.let { visibleUiState.seasonItemIds[it] }
                val seasonWatched = episodeSeason?.let { visibleUiState.seasonWatchStates[it] } ?: false
                val episodeMeta =
                    listOfNotNull(
                        episodeSeason?.let { "Season $it" },
                        selectedEpisode.episode?.let { "Episode $it" },
                    ).joinToString(" · ")
                val episodeActions = buildList {
                    add(
                        ItemActionSheetItem(
                            label = if (watchState.isWatched) "Unmark as watched" else "Mark as watched",
                            supporting = "This episode only",
                            icon = if (watchState.isWatched) Res.drawable.ic_check_filled else Res.drawable.ic_check,
                            filled = watchState.isWatched,
                            onClick = {
                                onToggleEpisodeWatched(selectedEpisode)
                            },
                        ),
                    )
                    if (episodeSeason != null && seasonItemId != null) {
                        add(
                            ItemActionSheetItem(
                                label =
                                    if (seasonWatched) {
                                        "Unmark season $episodeSeason as watched"
                                    } else {
                                        "Mark season $episodeSeason as watched"
                                    },
                                supporting = "All episodes in this season",
                                icon = if (seasonWatched) Res.drawable.ic_done_all_filled else Res.drawable.ic_done_all,
                                filled = seasonWatched,
                                dividerBefore = true,
                                onClick = {
                                    onToggleSeasonWatched(seasonItemId, episodeSeason)
                                },
                            ),
                        )
                    }
                }
                ModalBottomSheet(
                    onDismissRequest = { selectedEpisodeAction = null },
                    sheetState = episodeSheetState,
                ) {
                    ItemActionSheet(
                        title = selectedEpisode.title,
                        subtitle = listOfNotNull(episodeMeta.takeIf { it.isNotBlank() }, showTitle?.takeIf { it.isNotBlank() })
                            .joinToString(" · "),
                        imageUrl =
                            selectedEpisode.thumbnailUrl
                                ?.trim()
                                ?.takeIf { it.isNotBlank() }
                                ?: visibleDetails?.artworkUrl,
                        actions = episodeActions,
                    )
                }
            }

            if (visibleUiState.aiStoryVisible && visibleUiState.aiInsights != null) {
                val aiOverlayTitle = visibleDetails?.title ?: details?.title
                val aiOverlayArtworkUrl = visibleDetails?.artworkUrl ?: details?.artworkUrl
                val shareTitle = aiOverlayTitle?.trim()?.takeIf { it.isNotEmpty() } ?: "this title"
                AiInsightsStoryOverlay(
                    result = visibleUiState.aiInsights,
                    backdropUrls = aiBackdropUrls,
                    onDismiss = onDismissAiInsights,
                     artworkUrl = aiOverlayArtworkUrl,
                    palette = palette,
                    isInWatchlist = visibleUiState.isInWatchlist,
                    onToggleWatchlist = onToggleWatchlist,
                    onShare = { shareText("Check out $shareTitle on Crispy") },
                )
            }
        }
    }
}

/**
 * Does this trailer have to fall back to the embedded player?
 *
 * One decision that the file used to write out twice, as the same two-clause
 * condition in two places that then did different things with the answer -- the
 * autoplay `LaunchedEffect` returned early, and the toggle promoted the source to
 * an embed. Two copies of a rule is how they drift apart; this is the copy.
 *
 * The capability arrives as the **value** `youtubeTrailerPlaybackSupported` and not
 * as `AppDistribution`, because `AppDistribution` is declared in `androidMain` (its
 * installer is `CrispyApplication`) and naming it in a `commonMain` signature would
 * re-create the pin. It is a pure function of two common types, so unlike the other
 * decisions in this file it became callable from `commonTest` the moment it was
 * named.
 *
 * Only a YOUTUBE source can need the fallback. A direct source is served by the
 * native engine regardless of what the hero supports, and an unknown source
 * (`null`) is not a trailer to begin with -- which is why this is not simply
 * `!youtubePlaybackSupported`.
 */
internal fun trailerNeedsEmbedFallback(
    youtubePlaybackSupported: Boolean,
    source: TrailerSource?,
): Boolean = !youtubePlaybackSupported && source == TrailerSource.YOUTUBE

private fun HeroTrailerSource.toEmbeddedVideo(): MetadataVideoView =
    MetadataVideoView(
        id = id,
        key = extractYouTubeVideoId(id) ?: id,
        name = "Trailer",
        site = "YouTube",
        type = "Trailer",
        official = true,
        publishedAt = null,
        url = null,
        thumbnailUrl = null,
    )

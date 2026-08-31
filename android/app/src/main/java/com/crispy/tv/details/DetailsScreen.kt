@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.crispy.tv.details

import android.content.Intent
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.SheetValue
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import com.crispy.tv.ui.components.ItemActionSheet
import com.crispy.tv.ui.components.ItemActionSheetItem
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.settings.PlaybackSettings
import com.crispy.tv.addons.streams.AddonStream
import com.crispy.tv.addons.model.MediaVideo
import com.crispy.tv.details.trailer.TrailerSource
import com.crispy.tv.details.trailer.classifyTrailerSource
import com.crispy.tv.details.trailer.extractYouTubeVideoId
import com.crispy.tv.ui.edge_to_edge.safeBottomPadding
import com.crispy.tv.ui.navigation.LocalNavAnimatedContentScope
import com.crispy.tv.ui.navigation.animateContentAlpha
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import kotlinx.coroutines.delay

private val HERO_TRAILER_STOP_SCROLL_THRESHOLD = 120.dp

@Composable
internal fun DetailsScreen(
    uiState: DetailsUiState,
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
    onRetryProvider: (String) -> Unit,
    onStreamSelected: (AddonStream) -> Unit,
    onToggleWatchlist: () -> Unit,
    onToggleWatched: () -> Unit,
    onSetRating: (Int?) -> Unit,
    onTrailerMutedChanged: (Boolean) -> Unit,
    onAiInsightsClick: () -> Unit,
    onDismissAiInsights: () -> Unit,
) {
    val details = uiState.details
    val aiBackdropUrls =
        remember(details) {
            buildList {
                details?.artworkUrl?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            }.distinct()
        }
    val listState = rememberLazyListState()
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageUrl = remember(details, initialArtworkUrl) {
        detailsHeroImageUrl(details = details) ?: initialArtworkUrl
    }
    val fallbackSeed = Color.White
    val rawSeed by rememberSeedColor(imageUrl = imageUrl, fallbackSeed = fallbackSeed)
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
        if (!YouTubeInHeroPlaybackSupported &&
            heroTrailerSources.firstOrNull()?.source == TrailerSource.YOUTUBE
        ) {
            return@LaunchedEffect
        }

        delay(2000)
        showTrailer = true
    }

    val trailerPlaybackBlocked =
        visibleUiState.streamSelector.visible || visibleUiState.aiStoryVisible || !isScreenResumed

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

    var selectedMakingOfVideo by remember { mutableStateOf<CrispyBackendClient.MetadataVideoView?>(null) }
    var selectedTrailerEmbed by remember { mutableStateOf<CrispyBackendClient.MetadataVideoView?>(null) }
    var expandedReview by remember { mutableStateOf<CrispyBackendClient.MetadataReviewView?>(null) }
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
                        showTrailer = showTrailer,
                        isTrailerPlaying = isTrailerPlaying,
                        isTrailerMuted = userMutedTrailer,
                        onHeroImageLoaded = {},
                        onHeroImageLoadFailed = {},
                        onToggleTrailer = {
                            if (!trailerKey.isNullOrBlank()) {
                                val primary = heroTrailerSources.firstOrNull()
                                if (!YouTubeInHeroPlaybackSupported && primary?.source == TrailerSource.YOUTUBE) {
                                    selectedTrailerEmbed = primary.toEmbeddedVideo()
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
                    )
                }

                item(key = "header") {
                    HeaderInfoSection(
                        details = visibleDetails,
                        isInWatchlist = visibleUiState.isInWatchlist,
                        isWatched = visibleUiState.isWatched || visibleUiState.isShowFullyWatched,
                        isRated = visibleUiState.isRated,
                        userRating = visibleUiState.userRating,
                        optimisticSync = visibleUiState.optimisticSync,
                        palette = palette,
                        watchCta = visibleUiState.watchCta,
                        aiInsightsIsLoading = visibleUiState.aiIsLoading,
                        onAiInsightsClick = onAiInsightsClick,
                        onWatchNow = onOpenStreamSelector,
                        onToggleWatchlist = onToggleWatchlist,
                        onToggleWatched = onToggleWatched,
                        onSetRating = onSetRating,
                    )
                }

                detailsBodyContent(
                    uiState = visibleUiState,
                    horizontalPadding = bodyHorizontalPadding,
                    palette = palette,
                    onRetry = onRetry,
                    onSeasonSelected = onSeasonSelected,
                    onItemClick = onItemClick,
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
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = contentColor
                        )
                    }
                },
                actions = {
                    if (showTrailer && !trailerKey.isNullOrBlank()) {
                        IconButton(onClick = { onTrailerMutedChanged(!userMutedTrailer) }) {
                            Icon(
                                imageVector = if (userMutedTrailer) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = if (userMutedTrailer) "Unmute trailer" else "Mute trailer",
                                tint = contentColor,
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

            YouTubeExtraVideoDialog(
                video = selectedMakingOfVideo,
                onDismiss = { selectedMakingOfVideo = null },
            )

            YouTubeExtraVideoDialog(
                video = selectedTrailerEmbed,
                onDismiss = { selectedTrailerEmbed = null },
            )

            StreamSelectorBottomSheet(
                details = visibleDetails,
                state = visibleUiState.streamSelector,
                palette = palette,
                onDismiss = onDismissStreamSelector,
                onProviderSelected = onProviderSelected,
                onRetryProvider = onRetryProvider,
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
                                            imageVector = Icons.Filled.Star,
                                            contentDescription = null,
                                            tint = Color(0xFFFFD54F),
                                        )
                                        Text("${it.toInt()}/10", style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                            ReviewProviderBadge(provider = review.provider)
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
                            icon = if (watchState.isWatched) Icons.Filled.Check else Icons.Outlined.Check,
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
                                icon = if (seasonWatched) Icons.Filled.DoneAll else Icons.Outlined.DoneAll,
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
                    onShare = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "Check out $shareTitle on Crispy")
                        }
                        context.startActivity(Intent.createChooser(intent, "Share $shareTitle"))
                    },
                )
            }
        }
    }
}

private fun HeroTrailerSource.toEmbeddedVideo(): CrispyBackendClient.MetadataVideoView =
    CrispyBackendClient.MetadataVideoView(
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

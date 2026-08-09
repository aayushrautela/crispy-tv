@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.crispy.tv.details

import android.content.Intent
import androidx.compose.animation.animateEnterExit
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.settings.PlaybackSettings
import com.crispy.tv.streams.AddonStream
import com.crispy.tv.home.MediaVideo
import com.crispy.tv.ui.edge_to_edge.safeBottomPadding
import com.crispy.tv.ui.navigation.LocalNavAnimatedContentScope
import com.crispy.tv.ui.navigation.SharedElementDurationMillis
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import kotlinx.coroutines.delay

private val HERO_TRAILER_STOP_SCROLL_THRESHOLD = 120.dp

@Composable
internal fun DetailsScreen(
    uiState: DetailsUiState,
    playbackSettings: PlaybackSettings,
    initialBackdropUrl: String? = null,
    initialLogoUrl: String? = null,
    onBack: () -> Unit,
    onItemClick: (CatalogItem) -> Unit,
    onPersonClick: (String) -> Unit,
    onRetry: () -> Unit,
    onSeasonSelected: (Int) -> Unit,
    onOpenStreamSelector: () -> Unit,
    onEpisodeClick: (String) -> Unit,
    onToggleEpisodeWatched: (MediaVideo) -> Unit,
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
                details?.backdropUrl?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
                details?.posterUrl?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            }.distinct()
        }
    val listState = rememberLazyListState()
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageUrl = remember(details, initialBackdropUrl) {
        detailsHeroImageUrl(details = details) ?: initialBackdropUrl
    }
    val logoUrl = remember(details, initialLogoUrl) {
        details?.logoUrl?.trim()?.takeIf { it.isNotEmpty() } ?: initialLogoUrl
    }
    val baseScheme = MaterialTheme.colorScheme
    val fallbackSeed = baseScheme.primary
    val cachedSeed = remember(imageUrl) { cachedDetailsSeedColor(imageUrl) }
    var seedColor by remember(imageUrl, fallbackSeed) { mutableStateOf(cachedSeed ?: fallbackSeed) }
    var isSeedColorResolved by remember(imageUrl, fallbackSeed) {
        mutableStateOf(details == null || imageUrl.isNullOrBlank() || cachedSeed != null)
    }

    LaunchedEffect(details, imageUrl, cachedSeed, fallbackSeed) {
        seedColor = cachedSeed ?: fallbackSeed
        isSeedColorResolved = details == null || imageUrl.isNullOrBlank() || cachedSeed != null
        if (details == null || imageUrl.isNullOrBlank() || cachedSeed != null) return@LaunchedEffect

        val resolvedSeed = loadDetailsSeedColor(
            context = context,
            imageUrl = imageUrl,
            fallbackSeed = fallbackSeed,
        )
        seedColor = resolvedSeed ?: fallbackSeed
        isSeedColorResolved = true
    }

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

    val showPalettePlaceholder =
        details != null &&
            !imageUrl.isNullOrBlank() &&
            !isSeedColorResolved

    val visibleDetails = if (showPalettePlaceholder) null else details
    val visibleUiState = if (showPalettePlaceholder) uiState.copy(details = null, isLoading = true) else uiState

    val detailsScheme = rememberDetailsColorScheme(seedColor = seedColor)
    val palette = remember(detailsScheme) { detailsPaletteFromScheme(detailsScheme) }

    val selectedTrailer =
        uiState.titleDetail
            ?.videos
            ?.firstOrNull { it.key.isNotBlank() && it.official && it.type.equals("Trailer", true) }
            ?: uiState.titleDetail
                ?.videos
                ?.firstOrNull { it.key.isNotBlank() && it.type.equals("Trailer", true) }
            ?: uiState.titleDetail
                ?.videos
                ?.firstOrNull { it.key.isNotBlank() }

    val trailerKey = selectedTrailer?.key?.trim().takeIf { !it.isNullOrBlank() }

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

    val snackbarHostState = remember { SnackbarHostState() }
    var lastSnackMessage by remember { mutableStateOf("") }
    LaunchedEffect(uiState.statusMessage) {
        val message = uiState.statusMessage.trim()
        if (message.isBlank()) return@LaunchedEffect
        if (visibleDetails == null && visibleUiState.isLoading) return@LaunchedEffect
        if (message == lastSnackMessage) return@LaunchedEffect

        lastSnackMessage = message
        snackbarHostState.showSnackbar(message)
    }

    var selectedMakingOfVideo by remember { mutableStateOf<CrispyBackendClient.MetadataVideoView?>(null) }
    var expandedReview by remember { mutableStateOf<CrispyBackendClient.MetadataReviewView?>(null) }
    var selectedEpisodeAction by remember { mutableStateOf<MediaVideo?>(null) }
    val reviewSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val episodeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val bodyHorizontalPadding = responsivePageHorizontalPadding()

    MaterialTheme(colorScheme = detailsScheme) {
        val animatedVisibilityScope = LocalNavAnimatedContentScope.current
        val enterFadeIn = fadeIn(animationSpec = tween(SharedElementDurationMillis))
        val exitFadeOut = fadeOut(animationSpec = tween(SharedElementDurationMillis))
        Box(modifier = Modifier
            .fillMaxSize()
            .then(
                if (animatedVisibilityScope != null) {
                    Modifier.animateEnterExit(
                        animatedVisibilityScope = animatedVisibilityScope,
                        enter = enterFadeIn,
                        exit = exitFadeOut,
                    )
                } else {
                    Modifier
                }
            )
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
                        logoUrl = logoUrl,
                        palette = palette,
                        trailerKey = trailerKey,
                        showTrailer = showTrailer,
                        isTrailerPlaying = isTrailerPlaying,
                        isTrailerMuted = userMutedTrailer,
                        onHeroImageLoaded = {},
                        onHeroImageLoadFailed = {},
                        onToggleTrailer = {
                            if (!trailerKey.isNullOrBlank()) {
                                if (!showTrailer) {
                                    showTrailer = true
                                    userPausedTrailer = false
                                } else {
                                    userPausedTrailer = !userPausedTrailer
                                }
                            }
                        },
                        itemId = uiState.itemId,
                    )
                }

                item(key = "header") {
                    HeaderInfoSection(
                        details = visibleDetails,
                        isInWatchlist = visibleUiState.isInWatchlist,
                        isWatched = visibleUiState.isWatched,
                        isRated = visibleUiState.isRated,
                        userRating = visibleUiState.userRating,
                        isMutating = visibleUiState.isMutating,
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

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )

            YouTubeExtraVideoDialog(
                video = selectedMakingOfVideo,
                onDismiss = { selectedMakingOfVideo = null },
            )

            StreamSelectorBottomSheet(
                details = visibleDetails,
                state = visibleUiState.streamSelector,
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
                val toggleLabel = if (watchState.isWatched) "Mark as unwatched" else "Mark as watched"
                ModalBottomSheet(
                    onDismissRequest = { selectedEpisodeAction = null },
                    sheetState = episodeSheetState,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(top = 6.dp, bottom = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(selectedEpisode.title, style = MaterialTheme.typography.titleMedium)
                        formatLongDate(selectedEpisode.released)?.let { released ->
                            Text(
                                text = released,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            onClick = {
                                selectedEpisodeAction = null
                                onEpisodeClick(selectedEpisode.id)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Open episode") }
                        TextButton(
                            onClick = {
                                selectedEpisodeAction = null
                                onToggleEpisodeWatched(selectedEpisode)
                            },
                            modifier = Modifier.align(Alignment.End),
                        ) { Text(toggleLabel) }
                    }
                }
            }

            if (visibleUiState.aiStoryVisible && visibleUiState.aiInsights != null) {
                val aiOverlayTitle = visibleDetails?.title ?: details?.title
                val aiOverlayPosterUrl = visibleDetails?.posterUrl ?: details?.posterUrl
                val aiOverlayBackdropUrl = visibleDetails?.backdropUrl ?: details?.backdropUrl
                val shareTitle = aiOverlayTitle?.trim()?.takeIf { it.isNotEmpty() } ?: "this title"
                AiInsightsStoryOverlay(
                    result = visibleUiState.aiInsights,
                    backdropUrls = aiBackdropUrls,
                    onDismiss = onDismissAiInsights,
                    title = aiOverlayTitle,
                    posterUrl = aiOverlayPosterUrl,
                    backdropUrl = aiOverlayBackdropUrl,
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

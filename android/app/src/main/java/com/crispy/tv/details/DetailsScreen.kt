@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.crispy.tv.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.crispy.tv.settings.AiInsightsMode
import com.crispy.tv.settings.PlaybackSettings
import com.crispy.tv.streams.AddonStream
import com.crispy.tv.home.MediaVideo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private const val PALETTE_MAX_WAIT_MS = 450L
private val HERO_TRAILER_STOP_SCROLL_THRESHOLD = 120.dp

@Composable
internal fun DetailsScreen(
    uiState: DetailsUiState,
    playbackSettings: PlaybackSettings,
    onBack: () -> Unit,
    onItemClick: (String, String) -> Unit,
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
    val imageUrl = details?.backdropUrl ?: details?.posterUrl
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val theming = rememberDetailsTheming(imageUrl = imageUrl)

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

    val isSeedColorResolvedState = rememberUpdatedState(theming.isSeedColorResolved)
    val colorSchemeState = rememberUpdatedState(theming.colorScheme)
    var lockedScheme by remember(imageUrl) { mutableStateOf<ColorScheme?>(null) }
    LaunchedEffect(imageUrl) {
        lockedScheme = null
        if (imageUrl.isNullOrBlank()) return@LaunchedEffect
        if (isSeedColorResolvedState.value) return@LaunchedEffect

        val resolvedInTime =
            withTimeoutOrNull(PALETTE_MAX_WAIT_MS) {
                snapshotFlow { isSeedColorResolvedState.value }.first { it }
            } != null

        if (!resolvedInTime) {
            lockedScheme = colorSchemeState.value
        }
    }

    val showPalettePlaceholder =
        details != null &&
            !imageUrl.isNullOrBlank() &&
            !theming.isSeedColorResolved &&
            lockedScheme == null

    val visibleDetails = if (showPalettePlaceholder) null else details
    val visibleUiState = if (showPalettePlaceholder) uiState.copy(details = null, isLoading = true) else uiState

    val detailsScheme = lockedScheme ?: theming.colorScheme
    val palette = remember(detailsScheme) { detailsPaletteFromScheme(detailsScheme) }
    val pullToRefreshState = rememberPullToRefreshState()

    val selectedTrailer =
        uiState.tmdbEnrichment
            ?.trailers
            ?.firstOrNull { it.key.isNotBlank() && it.official && it.type.equals("Trailer", true) }
            ?: uiState.tmdbEnrichment
                ?.trailers
                ?.firstOrNull { it.key.isNotBlank() && it.type.equals("Trailer", true) }
            ?: uiState.tmdbEnrichment
                ?.trailers
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

    MaterialTheme(colorScheme = detailsScheme) {
        Box(modifier = Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = visibleUiState.isLoading && visibleDetails != null,
                onRefresh = {
                    if (!visibleUiState.isLoading) {
                        onRetry()
                    }
                },
                modifier = Modifier.fillMaxSize(),
                state = pullToRefreshState,
                indicator = {
                    PullToRefreshDefaults.LoadingIndicator(
                        state = pullToRefreshState,
                        isRefreshing = visibleUiState.isLoading && visibleDetails != null,
                        modifier = Modifier.align(Alignment.TopCenter).zIndex(1f),
                    )
                },
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .navigationBarsPadding(),
                    state = listState
                ) {
                    item {
                        HeroSection(
                            details = details,
                            palette = palette,
                            trailerKey = trailerKey,
                            showTrailer = showTrailer,
                            isTrailerPlaying = isTrailerPlaying,
                            isTrailerMuted = userMutedTrailer,
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
                            onToggleTrailerMute = {
                                onTrailerMutedChanged(!userMutedTrailer)
                            }
                        )
                    }

                    item {
                        HeaderInfoSection(
                            details = visibleDetails,
                            isInWatchlist = visibleUiState.isInWatchlist,
                            isWatched = visibleUiState.isWatched,
                            isRated = visibleUiState.isRated,
                            userRating = visibleUiState.userRating,
                            isMutating = visibleUiState.isMutating,
                            palette = palette,
                            watchCta = visibleUiState.watchCta,
                            showAiInsights = visibleUiState.aiMode != AiInsightsMode.OFF,
                            aiInsightsEnabled = visibleUiState.aiConfigured,
                            aiInsightsIsLoading = visibleUiState.aiIsLoading,
                            onAiInsightsClick = onAiInsightsClick,
                            onWatchNow = onOpenStreamSelector,
                            onToggleWatchlist = onToggleWatchlist,
                            onToggleWatched = onToggleWatched,
                            onSetRating = onSetRating
                        )
                    }

                    item {
                        DetailsBody(
                            uiState = visibleUiState,
                            onRetry = onRetry,
                            onSeasonSelected = onSeasonSelected,
                            onItemClick = onItemClick,
                            onPersonClick = onPersonClick,
                            onEpisodeClick = onEpisodeClick,
                            onToggleEpisodeWatched = onToggleEpisodeWatched,
                        )
                    }
                }
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

            StreamSelectorBottomSheet(
                details = visibleDetails,
                state = visibleUiState.streamSelector,
                onDismiss = onDismissStreamSelector,
                onProviderSelected = onProviderSelected,
                onRetryProvider = onRetryProvider,
                onStreamSelected = onStreamSelected,
            )

            if (visibleUiState.aiStoryVisible && visibleUiState.aiInsights != null) {
                AiInsightsStoryOverlay(
                    result = visibleUiState.aiInsights,
                    imageUrl = visibleDetails?.backdropUrl ?: visibleDetails?.posterUrl,
                    onDismiss = onDismissAiInsights,
                )
            }
        }
    }
}

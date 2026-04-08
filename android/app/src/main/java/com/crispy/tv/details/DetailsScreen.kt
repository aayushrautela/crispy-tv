@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.crispy.tv.details

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.settings.PlaybackSettings
import com.crispy.tv.streams.AddonStream
import com.crispy.tv.home.MediaVideo
import kotlinx.coroutines.delay

private val HERO_TRAILER_STOP_SCROLL_THRESHOLD = 120.dp

@Composable
internal fun DetailsScreen(
    uiState: DetailsUiState,
    playbackSettings: PlaybackSettings,
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
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val screenWidthPx = remember(configuration.screenWidthDp, density) {
        with(density) { configuration.screenWidthDp.dp.toPx() }
    }
    val imageUrl = remember(details, screenWidthPx) {
        detailsHeroImageUrl(details = details, backdropSize = detailsHeroBackdropSize(screenWidthPx))
    }
    val baseScheme = MaterialTheme.colorScheme
    val fallbackSeed = baseScheme.primary
    val cachedSeed = remember(imageUrl) { cachedDetailsSeedColor(imageUrl) }
    var seedColor by remember(imageUrl, fallbackSeed) { mutableStateOf(cachedSeed ?: fallbackSeed) }
    var isSeedColorResolved by remember(imageUrl, fallbackSeed) {
        mutableStateOf(details == null || imageUrl.isNullOrBlank() || cachedSeed != null)
    }
    var loadedHeroBitmap by remember(imageUrl) { mutableStateOf<Bitmap?>(null) }
    var heroImageLoadFailed by remember(imageUrl) { mutableStateOf(false) }

    LaunchedEffect(details, imageUrl, cachedSeed, fallbackSeed, loadedHeroBitmap, heroImageLoadFailed) {
        seedColor = cachedSeed ?: fallbackSeed
        isSeedColorResolved = details == null || imageUrl.isNullOrBlank() || cachedSeed != null
        if (details == null || imageUrl.isNullOrBlank() || cachedSeed != null) return@LaunchedEffect

        val bitmap = loadedHeroBitmap
        if (bitmap == null && !heroImageLoadFailed) return@LaunchedEffect

        val resolvedSeed =
            if (bitmap != null) {
                computeDetailsSeedColor(bitmap = bitmap, fallbackSeed = fallbackSeed)?.also {
                    cacheDetailsSeedColor(imageUrl, it)
                }
            } else {
                null
            }
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

    MaterialTheme(colorScheme = detailsScheme) {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .navigationBarsPadding(),
                state = listState,
            ) {
                item {
                    HeroSection(
                        details = visibleDetails,
                        imageUrl = imageUrl,
                        palette = palette,
                        trailerKey = trailerKey,
                        showTrailer = showTrailer,
                        isTrailerPlaying = isTrailerPlaying,
                        isTrailerMuted = userMutedTrailer,
                        onHeroImageLoaded = { bitmap ->
                            if (cachedSeed == null && loadedHeroBitmap == null) {
                                loadedHeroBitmap = bitmap
                                heroImageLoadFailed = false
                            }
                        },
                        onHeroImageLoadFailed = {
                            if (cachedSeed == null && loadedHeroBitmap == null) {
                                heroImageLoadFailed = true
                            }
                        },
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
                        aiInsightsIsLoading = visibleUiState.aiIsLoading,
                        onAiInsightsClick = onAiInsightsClick,
                        onWatchNow = onOpenStreamSelector,
                        onToggleWatchlist = onToggleWatchlist,
                        onToggleWatched = onToggleWatched,
                        onSetRating = onSetRating,
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
                    backdropUrls = aiBackdropUrls,
                    onDismiss = onDismissAiInsights,
                )
            }
        }
    }
}

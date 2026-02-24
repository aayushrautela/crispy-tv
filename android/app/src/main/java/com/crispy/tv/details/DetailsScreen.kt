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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crispy.tv.settings.AiInsightsMode
import com.crispy.tv.streams.AddonStream
import kotlinx.coroutines.delay

@Composable
internal fun DetailsScreen(
    uiState: DetailsUiState,
    onBack: () -> Unit,
    onItemClick: (String, String) -> Unit,
    onRetry: () -> Unit,
    onSeasonSelected: (Int) -> Unit,
    onOpenStreamSelector: () -> Unit,
    onEpisodeClick: (String) -> Unit,
    onDismissStreamSelector: () -> Unit,
    onProviderSelected: (String?) -> Unit,
    onRetryProvider: (String) -> Unit,
    onStreamSelected: (AddonStream) -> Unit,
    onToggleWatchlist: () -> Unit,
    onToggleWatched: () -> Unit,
    onSetRating: (Int?) -> Unit,
    onAiInsightsClick: () -> Unit,
    onDismissAiInsights: () -> Unit,
) {
    val details = uiState.details
    val listState = rememberLazyListState()
    val theming = rememberDetailsTheming(imageUrl = details?.backdropUrl ?: details?.posterUrl)
    val palette = theming.palette
    val detailsScheme = theming.colorScheme

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
    val trailerWatchUrl = selectedTrailer?.watchUrl?.trim().takeIf { !it.isNullOrBlank() }

    val heroIsPinned by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= 2
        }
    }

    var showTrailer by rememberSaveable(trailerKey) { mutableStateOf(false) }
    var userPausedTrailer by rememberSaveable(trailerKey) { mutableStateOf(false) }
    var userMutedTrailer by rememberSaveable(trailerKey) { mutableStateOf(true) }

    LaunchedEffect(trailerKey) {
        showTrailer = false
        userPausedTrailer = false
        userMutedTrailer = true

        if (trailerKey.isNullOrBlank()) return@LaunchedEffect

        delay(2000)
        showTrailer = true
    }

    val isTrailerPlaying =
        showTrailer &&
            heroIsPinned &&
            !listState.isScrollInProgress &&
            !userPausedTrailer

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
        if (details == null && uiState.isLoading) return@LaunchedEffect
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
                state = listState
            ) {
                item {
                    HeroSection(
                        details = details,
                        palette = palette,
                        trailerKey = trailerKey,
                        trailerWatchUrl = trailerWatchUrl,
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
                            userMutedTrailer = !userMutedTrailer
                        }
                    )
                }

                item {
                    HeaderInfoSection(
                        details = details,
                        isInWatchlist = uiState.isInWatchlist,
                        isWatched = uiState.isWatched,
                        isRated = uiState.isRated,
                        userRating = uiState.userRating,
                        isMutating = uiState.isMutating,
                        palette = palette,
                        watchCta = uiState.watchCta,
                        showAiInsights = uiState.aiMode != AiInsightsMode.OFF,
                        aiInsightsEnabled = uiState.aiConfigured,
                        aiInsightsIsLoading = uiState.aiIsLoading,
                        onAiInsightsClick = onAiInsightsClick,
                        onWatchNow = onOpenStreamSelector,
                        onToggleWatchlist = onToggleWatchlist,
                        onToggleWatched = onToggleWatched,
                        onSetRating = onSetRating
                    )
                }

                item {
                    DetailsBody(
                        uiState = uiState,
                        onRetry = onRetry,
                        onSeasonSelected = onSeasonSelected,
                        onItemClick = onItemClick,
                        onEpisodeClick = onEpisodeClick,
                    )
                }
            }

            TopAppBar(
                windowInsets = TopAppBarDefaults.windowInsets,
                title = {
                    Text(
                        text = if (topBarAlpha > 0.65f) details?.title ?: "Details" else "",
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
                details = details,
                state = uiState.streamSelector,
                onDismiss = onDismissStreamSelector,
                onProviderSelected = onProviderSelected,
                onRetryProvider = onRetryProvider,
                onStreamSelected = onStreamSelected,
            )

            if (uiState.aiStoryVisible && uiState.aiInsights != null) {
                AiInsightsStoryOverlay(
                    result = uiState.aiInsights,
                    imageUrl = details?.backdropUrl ?: details?.posterUrl,
                    onDismiss = onDismissAiInsights,
                )
            }
        }
    }
}

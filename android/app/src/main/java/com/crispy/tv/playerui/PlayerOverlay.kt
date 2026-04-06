package com.crispy.tv.playerui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.crispy.tv.details.DetailsPaletteColors
import com.crispy.tv.home.MediaDetails
import com.crispy.tv.home.MediaVideo
import com.crispy.tv.streams.AddonStream
import kotlinx.coroutines.delay

@Composable
internal fun PlayerOverlay(
    uiState: PlayerUiState,
    palette: DetailsPaletteColors,
    onBack: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onShowInfo: () -> Unit,
    onShowStreams: () -> Unit,
    onCloseSurface: () -> Unit,
    onSeasonSelected: (Int) -> Unit,
    onEpisodeSelected: (String) -> Unit,
    onProviderSelected: (String?) -> Unit,
    onRetryProvider: (String) -> Unit,
    onStreamSelected: (AddonStream) -> Unit,
    onRetryPlayback: () -> Unit,
) {
    val overlayPadding = rememberOverlayPadding(minPadding = 20.dp)
    val effectiveDurationMs = if (uiState.stableDurationMs > 0L) uiState.stableDurationMs else uiState.durationMs

    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var controlsResetToken by remember { mutableStateOf(0) }
    var showLoadingCurtain by remember { mutableStateOf(uiState.isBuffering) }

    val latestOnBack by rememberUpdatedState(onBack)
    val latestOnTogglePlayPause by rememberUpdatedState(onTogglePlayPause)
    val latestOnSeekTo by rememberUpdatedState(onSeekTo)

    fun resetControlsTimer() {
        controlsResetToken += 1
    }

    fun openInfo() {
        onShowInfo()
        controlsVisible = true
        resetControlsTimer()
    }

    fun openStreams() {
        onShowStreams()
        controlsVisible = true
        resetControlsTimer()
    }

    val isSurfaceOpen = uiState.activeSurface != PlayerSurface.NONE || uiState.streamSelector.visible

    BackHandler(enabled = isSurfaceOpen) {
        onCloseSurface()
    }

    LaunchedEffect(controlsResetToken, controlsVisible, isSurfaceOpen, uiState.isPlaying, uiState.isBuffering, uiState.errorMessage) {
        if (!controlsVisible) return@LaunchedEffect
        if (isSurfaceOpen) return@LaunchedEffect
        if (!uiState.isPlaying) return@LaunchedEffect
        if (uiState.isBuffering) return@LaunchedEffect
        if (uiState.errorMessage != null) return@LaunchedEffect

        delay(4_000)
        controlsVisible = false
    }

    LaunchedEffect(uiState.isBuffering, uiState.errorMessage) {
        if (uiState.errorMessage != null) {
            showLoadingCurtain = false
            return@LaunchedEffect
        }

        if (uiState.isBuffering) {
            showLoadingCurtain = true
        } else {
            delay(300)
            showLoadingCurtain = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(isSurfaceOpen, controlsVisible) {
                        detectTapGestures(
                            onTap = {
                                if (isSurfaceOpen) {
                                    onCloseSurface()
                                    return@detectTapGestures
                                }

                                controlsVisible = !controlsVisible
                                if (controlsVisible) {
                                    resetControlsTimer()
                                }
                            },
                        )
                    },
        )

        PlayerLoadingCurtain(
            visible = showLoadingCurtain,
            text =
                when {
                    uiState.errorMessage != null -> uiState.errorMessage
                    uiState.statusMessage.isNotBlank() -> uiState.statusMessage
                    else -> "Loading..."
                },
            modifier = Modifier.align(Alignment.Center),
        )

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(overlayPadding),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    PlayerTopBar(
                        title = uiState.title,
                        subtitle = uiState.subtitle ?: uiState.statusMessage.takeIf { it.isNotBlank() && it != "Playing" },
                        errorMessage = uiState.errorMessage,
                        onBack = {
                            resetControlsTimer()
                            latestOnBack()
                        },
                    )

                    FilledIconButton(
                        onClick = {
                            resetControlsTimer()
                            latestOnTogglePlayPause()
                        },
                        modifier =
                            Modifier
                                .align(Alignment.CenterHorizontally)
                                .size(84.dp),
                    ) {
                        Icon(
                            imageVector = if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(44.dp),
                        )
                    }

                    PlayerBottomControls(
                        positionMs = uiState.positionMs,
                        durationMs = effectiveDurationMs,
                        onSeekTo = {
                            resetControlsTimer()
                            latestOnSeekTo(it)
                        },
                        onOpenStreams = ::openStreams,
                        onOpenInfo = ::openInfo,
                    )
                }
            }
        }

        PlayerErrorCard(
            errorMessage = uiState.errorMessage,
            onRetry = {
                resetControlsTimer()
                onRetryPlayback()
            },
            modifier = Modifier.align(Alignment.Center),
        )

        PlayerInfoSheet(
            visible = uiState.activeSurface == PlayerSurface.INFO,
            details = uiState.details,
            seasons = uiState.seasons,
            selectedSeason = uiState.selectedSeason,
            seasonEpisodes = uiState.seasonEpisodes,
            currentEpisodeId = uiState.currentEpisodeId,
            episodesIsLoading = uiState.episodesIsLoading,
            episodesStatusMessage = uiState.episodesStatusMessage,
            palette = palette,
            onClose = onCloseSurface,
            onSeasonSelected = {
                resetControlsTimer()
                onSeasonSelected(it)
            },
            onEpisodeSelected = { episodeId ->
                resetControlsTimer()
                onEpisodeSelected(episodeId)
            },
        )

        PlayerStreamsSheet(
            visible = uiState.streamSelector.visible,
            details = uiState.details,
            state = uiState.streamSelector,
            onDismiss = onCloseSurface,
            onProviderSelected = {
                resetControlsTimer()
                onProviderSelected(it)
            },
            onRetryProvider = {
                resetControlsTimer()
                onRetryProvider(it)
            },
            onStreamSelected = { stream ->
                resetControlsTimer()
                onStreamSelected(stream)
            },
        )
    }
}

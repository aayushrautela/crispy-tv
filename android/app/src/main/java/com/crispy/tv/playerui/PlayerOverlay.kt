package com.crispy.tv.playerui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.crispy.tv.details.DetailsPaletteColors
import com.crispy.tv.home.MediaDetails
import com.crispy.tv.home.MediaVideo
import com.crispy.tv.streams.AddonStream
import com.crispy.tv.streams.AddonSubtitle
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
    onShowTracks: () -> Unit,
    onCloseSurface: () -> Unit,
    onSeasonSelected: (Int) -> Unit,
    onEpisodeSelected: (String) -> Unit,
    onProviderSelected: (String?) -> Unit,
    onRetryProvider: (String) -> Unit,
    onStreamSelected: (AddonStream) -> Unit,
    onRetryPlayback: () -> Unit,
    onSelectAudioTrack: (String?) -> Unit,
    onSelectSubtitleTrack: (String?) -> Unit,
    onFetchAddonSubtitles: () -> Unit,
    onSelectAddonSubtitle: (AddonSubtitle) -> Unit,
    onCycleResizeMode: () -> Unit,
    onDoubleTapSeek: (Long) -> Unit,
) {
    val overlayPadding = rememberOverlayPadding(minPadding = 20.dp)
    val effectiveDurationMs = if (uiState.stableDurationMs > 0L) uiState.stableDurationMs else uiState.durationMs

    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var controlsResetToken by remember { mutableStateOf(0) }
    var showLoadingCurtain by remember { mutableStateOf(uiState.isBuffering) }

    val latestOnBack by rememberUpdatedState(onBack)
    val latestOnTogglePlayPause by rememberUpdatedState(onTogglePlayPause)
    val latestOnSeekTo by rememberUpdatedState(onSeekTo)
    val latestOnDoubleTapSeek by rememberUpdatedState(onDoubleTapSeek)

    fun resetControlsTimer() {
        controlsResetToken += 1
    }

    fun openSurface(open: () -> Unit) {
        open()
        controlsVisible = true
        resetControlsTimer()
    }

    val isSurfaceOpen = uiState.activeSurface != PlayerSurface.NONE || uiState.streamSelector.visible

    var layoutWidthPx by remember { mutableIntStateOf(0) }

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
            delay(250)
            if (uiState.isBuffering) showLoadingCurtain = true
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
                    .onGloballyPositioned { coordinates ->
                        val width = coordinates.size.width
                        if (width > 0 && width != layoutWidthPx) {
                            layoutWidthPx = width
                        }
                    }
                    .pointerInput(isSurfaceOpen, controlsVisible, layoutWidthPx) {
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
                            onDoubleTap = { offset ->
                                if (isSurfaceOpen) return@detectTapGestures
                                if (layoutWidthPx <= 0) return@detectTapGestures
                                val positionMs = uiState.positionMs.coerceAtLeast(0L)
                                val maxMs = effectiveDurationMs.takeIf { it > 0L }
                                val targetMs = when {
                                    offset.x < layoutWidthPx * LEFT_GESTURE_BOUNDARY ->
                                        (positionMs - DOUBLE_TAP_SEEK_STEP_MS).coerceAtLeast(0L)
                                    offset.x > layoutWidthPx * RIGHT_GESTURE_BOUNDARY -> {
                                        val unclamped = positionMs + DOUBLE_TAP_SEEK_STEP_MS
                                        maxMs?.let { unclamped.coerceAtMost(it) } ?: unclamped
                                    }
                                    else -> return@detectTapGestures
                                }
                                latestOnDoubleTapSeek(targetMs)
                                controlsVisible = false
                            },
                        )
                    },
        )

        PlayerLoadingCurtain(
            visible = showLoadingCurtain,
            palette = palette,
            modifier = Modifier.align(Alignment.Center),
        )

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)),
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(overlayPadding)) {
                PlayerTopBar(
                    title = uiState.title,
                    subtitle = uiState.subtitle ?: uiState.statusMessage.takeIf { it.isNotBlank() && it != "Playing" },
                    errorMessage = uiState.errorMessage,
                    palette = palette,
                    onBack = {
                        resetControlsTimer()
                        latestOnBack()
                    },
                    onShowInfo = { openSurface(onShowInfo) },
                    modifier = Modifier.align(Alignment.TopCenter),
                )

                if (!uiState.isBuffering) {
                    FilledIconButton(
                        onClick = {
                            resetControlsTimer()
                            latestOnTogglePlayPause()
                        },
                        colors =
                            IconButtonDefaults.filledIconButtonColors(
                                containerColor = palette.accent,
                                contentColor = palette.onAccent,
                            ),
                        modifier =
                            Modifier
                                .align(Alignment.Center)
                                .size(84.dp),
                    ) {
                        Icon(
                            imageVector = if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(44.dp),
                        )
                    }
                }

                PlayerBottomControls(
                    positionMs = uiState.positionMs,
                    durationMs = effectiveDurationMs,
                    hasAudioTracks = uiState.audioTracks.isNotEmpty(),
                    hasSubtitleTracks = uiState.subtitleTracks.isNotEmpty(),
                    palette = palette,
                    onSeekTo = {
                        resetControlsTimer()
                        latestOnSeekTo(it)
                    },
                    onOpenStreams = { openSurface(onShowStreams) },
                    onOpenTracks = { openSurface(onShowTracks) },
                    onCycleResizeMode = {
                        resetControlsTimer()
                        onCycleResizeMode()
                    },
                    resizeMode = uiState.resizeMode,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
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

        PlayerTrackSheet(
            visible = uiState.activeSurface == PlayerSurface.TRACKS,
            audioTracks = uiState.audioTracks,
            selectedAudioTrackId = uiState.selectedAudioTrackId,
            subtitleTracks = uiState.subtitleTracks,
            selectedSubtitleTrackId = uiState.selectedSubtitleTrackId,
            addonSubtitles = uiState.addonSubtitles,
            addonSubtitlesLoading = uiState.addonSubtitlesLoading,
            addonSubtitlesError = uiState.addonSubtitlesError,
            selectedAddonSubtitleId = uiState.selectedAddonSubtitleId,
            onSelectAudioTrack = {
                resetControlsTimer()
                onSelectAudioTrack(it)
            },
            onSelectSubtitleTrack = {
                resetControlsTimer()
                onSelectSubtitleTrack(it)
            },
            onFetchAddonSubtitles = {
                resetControlsTimer()
                onFetchAddonSubtitles()
            },
            onSelectAddonSubtitle = { subtitle ->
                resetControlsTimer()
                onSelectAddonSubtitle(subtitle)
            },
            onDismiss = onCloseSurface,
        )
    }
}

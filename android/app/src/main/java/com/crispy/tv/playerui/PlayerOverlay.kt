package com.crispy.tv.playerui

import android.view.ViewConfiguration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.crispy.tv.details.DetailsPaletteColors
import com.crispy.tv.addons.model.MediaDetails
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.addons.model.MediaVideo
import com.crispy.tv.addons.streams.AddonStream
import com.crispy.tv.addons.streams.AddonSubtitle
import com.crispy.tv.addons.streams.StreamSelectorUiState
import com.crispy.tv.domain.player.TapSeekChain
import com.crispy.tv.domain.player.TapSeekEvent
import com.crispy.tv.streams.StreamSelectorSheet
import kotlinx.coroutines.delay

@Composable
internal fun PlayerOverlay(
    uiState: PlayerUiState,
    selectorState: StreamSelectorUiState,
    positionMsState: State<Long>,
    palette: DetailsPaletteColors,
    onBack: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onShowInfo: () -> Unit,
    onShowEpisodes: () -> Unit,
    onShowStreams: () -> Unit,
    onShowAudio: () -> Unit,
    onShowSubtitles: () -> Unit,
    onCloseSurface: () -> Unit,
    onProviderSelected: (String?) -> Unit,
    onStreamSelected: (AddonStream) -> Unit,
    onRetryPlayback: () -> Unit,
    onSelectAudioTrack: (String?) -> Unit,
    onSelectSubtitleTrack: (String?) -> Unit,
    onRefreshAddonSubtitles: () -> Unit,
    onSelectEpisode: (String) -> Unit,
    onSeasonSelected: (Int) -> Unit,
    onShowMore: () -> Unit,
    onOpenTitle: (CatalogItem) -> Unit,
    onCycleResizeMode: () -> Unit,
    onCommitSeek: (targetMs: Long, totalDeltaMs: Long) -> Unit,
) {
    val overlayPadding = rememberOverlayPadding(minPadding = 12.dp)
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    val tightBottomPadding =
        androidx.compose.foundation.layout.PaddingValues(
            start = overlayPadding.calculateLeftPadding(layoutDirection),
            end = overlayPadding.calculateRightPadding(layoutDirection),
            top = overlayPadding.calculateTopPadding(),
            bottom = maxOf(4.dp, overlayPadding.calculateBottomPadding() - 8.dp),
        )
    val effectiveDurationMs = if (uiState.stableDurationMs > 0L) uiState.stableDurationMs else uiState.durationMs

    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var controlsResetToken by remember { mutableStateOf(0) }
    var showLoadingCurtain by remember { mutableStateOf(uiState.isBuffering) }
    var seekRipple by remember { mutableStateOf<SeekRippleState?>(null) }

    val doubleTapTimeoutMs = ViewConfiguration.getDoubleTapTimeout()
    val seekChain = remember(doubleTapTimeoutMs) { TapSeekChain(doubleTapWindowMs = doubleTapTimeoutMs.toLong()) }
    val tapGestureScope = rememberCoroutineScope()

    val latestOnBack by rememberUpdatedState(onBack)
    val latestOnTogglePlayPause by rememberUpdatedState(onTogglePlayPause)
    val latestOnSeekTo by rememberUpdatedState(onSeekTo)
    val latestOnCommitSeek by rememberUpdatedState(onCommitSeek)
    val latestDurationMs by rememberUpdatedState(effectiveDurationMs)

    fun resetControlsTimer() {
        controlsResetToken += 1
    }

    fun openSurface(open: () -> Unit) {
        open()
        controlsVisible = true
        resetControlsTimer()
    }

    val isSurfaceOpen = uiState.activeSurface != PlayerSurface.NONE || selectorState.visible
    val latestIsSurfaceOpen by rememberUpdatedState(isSurfaceOpen)

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
                    .playerTapGestures(
                        chain = seekChain,
                        scope = tapGestureScope,
                        isSurfaceOpen = { latestIsSurfaceOpen },
                        positionMs = { positionMsState.value.coerceAtLeast(0L) },
                        durationMs = { latestDurationMs },
                        onSurfaceTap = onCloseSurface,
                        onEvents = { events ->
                            for (event in events) {
                                when (event) {
                                    TapSeekEvent.ToggleControls -> {
                                        controlsVisible = !controlsVisible
                                        if (controlsVisible) resetControlsTimer()
                                    }
                                    TapSeekEvent.RevertControls -> controlsVisible = !controlsVisible
                                    is TapSeekEvent.ChainStarted -> {
                                        controlsVisible = false
                                        seekRipple = SeekRippleState(event.side, event.pendingDeltaMs, event.count)
                                    }
                                    is TapSeekEvent.ChainExtended -> {
                                        seekRipple = SeekRippleState(event.side, event.pendingDeltaMs, event.count)
                                    }
                                    is TapSeekEvent.ChainCommitted -> {
                                        seekRipple = null
                                        latestOnCommitSeek(event.targetMs, event.totalDeltaMs)
                                    }
                                }
                            }
                        },
                    ),
        )

        PlayerLoadingCurtain(
            visible = showLoadingCurtain,
            palette = palette,
            modifier = Modifier.align(Alignment.Center),
        )

        SeekRippleOverlay(
            state = seekRipple,
            contentPadding = tightBottomPadding,
            modifier = Modifier.fillMaxSize(),
        )

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)),
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(tightBottomPadding)) {
                PlayerTopBar(
                    title = uiState.title,
                    subtitle = uiState.subtitle ?: uiState.statusMessage.takeIf { it.isNotBlank() && it != "Playing" },
                    errorMessage = uiState.errorMessage,
                    palette = palette,
                    isMetadataLoaded = uiState.isMetadataLoaded,
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

                val isSeries = uiState.details?.itemType?.equals("movie", ignoreCase = true) == false
                val pills =
                    if (isSeries) {
                        val episodesThumbUrl =
                            remember(uiState.seasonEpisodes, uiState.activeIdentity, uiState.artworkUrl) {
                                val active = uiState.activeIdentity
                                val sorted = uiState.seasonEpisodes.sortedBy { it.episode ?: Int.MAX_VALUE }
                                val currentIdx = sorted.indexOfFirst { it.episode == active?.episode && it.season == active?.season }
                                val thumb =
                                    when {
                                        currentIdx >= 0 && currentIdx + 1 < sorted.size -> sorted[currentIdx + 1].thumbnailUrl
                                        currentIdx >= 0 -> sorted[currentIdx].thumbnailUrl
                                        sorted.isNotEmpty() -> sorted.firstOrNull()?.thumbnailUrl
                                        else -> null
                                    }
                                thumb?.trim()?.takeIf { it.isNotBlank() } ?: uiState.artworkUrl
                            }
                        listOf(
                            PlayerActionPill(
                                label = "Episodes",
                                thumbUrl = episodesThumbUrl,
                                onClick = { openSurface(onShowEpisodes) },
                            ),
                        )
                    } else if (uiState.collectionItems.isNotEmpty() || uiState.recommendedItems.isNotEmpty()) {
                        listOf(
                            PlayerActionPill(
                                label = "More",
                                thumbUrl =
                                    uiState.collectionItems.firstOrNull()?.artworkUrl
                                        ?: uiState.recommendedItems.first().artworkUrl,
                                onClick = { openSurface(onShowMore) },
                            ),
                        )
                    } else {
                        emptyList<PlayerActionPill>()
                    }

                PlayerBottomControls(
                    positionMsState = positionMsState,
                    durationMs = effectiveDurationMs,
                    hasAudioTracks = uiState.audioTracks.isNotEmpty(),
                    palette = palette,
                    onSeekTo = {
                        resetControlsTimer()
                        latestOnSeekTo(it)
                    },
                    onOpenStreams = { openSurface(onShowStreams) },
                    onOpenAudio = { openSurface(onShowAudio) },
                    onOpenSubtitles = { openSurface(onShowSubtitles) },
                    onCycleResizeMode = {
                        resetControlsTimer()
                        onCycleResizeMode()
                    },
                    resizeMode = uiState.resizeMode,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    pills = pills,
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
            palette = palette,
            onClose = onCloseSurface,
            headerEpisode = selectorState.headerEpisode,
        )

        PlayerEpisodesSheet(
            visible = uiState.activeSurface == PlayerSurface.EPISODES,
            seasons = uiState.seasons,
            selectedSeason = uiState.selectedSeason,
            seasonEpisodes = uiState.seasonEpisodes,
            episodesIsLoading = uiState.episodesIsLoading,
            episodesStatusMessage = uiState.episodesStatusMessage,
            palette = palette,
            activeSeason = uiState.activeIdentity?.season,
            activeEpisode = uiState.activeIdentity?.episode,
            onSeasonSelected = {
                resetControlsTimer()
                onSeasonSelected(it)
            },
            onEpisodeSelected = {
                resetControlsTimer()
                onSelectEpisode(it)
            },
            onClose = onCloseSurface,
        )

        PlayerMoreSheet(
            visible = uiState.activeSurface == PlayerSurface.MORE,
            collectionItems = uiState.collectionItems,
            recommendedItems = uiState.recommendedItems,
            moreIsLoading = uiState.moreIsLoading,
            palette = palette,
            onItemSelected = onOpenTitle,
            onClose = onCloseSurface,
        )

        StreamSelectorSheet(
            visible = uiState.activeSurface == PlayerSurface.STREAMS,
            state = selectorState,
            details = uiState.details,
            headerEpisode = selectorState.headerEpisode,
            accentColor = palette.accent,
            onAccentColor = palette.onAccent,
            useCrispyImageModel = true,
            scrimColor = Color.Transparent,
            onDismiss = onCloseSurface,
            onProviderSelected = {
                resetControlsTimer()
                onProviderSelected(it)
            },
            onStreamSelected = { stream ->
                resetControlsTimer()
                onStreamSelected(stream)
            },
        )

        PlayerAudioSheet(
            visible = uiState.activeSurface == PlayerSurface.AUDIO,
            audioTracks = uiState.audioTracks,
            selectedAudioTrackId = uiState.selectedAudioTrackId,
            palette = palette,
            onSelectAudioTrack = {
                resetControlsTimer()
                onSelectAudioTrack(it)
            },
            onDismiss = onCloseSurface,
        )

        PlayerSubtitleSheet(
            visible = uiState.activeSurface == PlayerSurface.SUBTITLES,
            subtitleTracks = uiState.subtitleTracks,
            selectedSubtitleTrackId = uiState.selectedSubtitleTrackId,
            addonSubtitles = uiState.addonSubtitles,
            addonSubtitlesLoading = uiState.addonSubtitlesLoading,
            addonSubtitlesError = uiState.addonSubtitlesError,
            palette = palette,
            onSelectSubtitleTrack = {
                resetControlsTimer()
                onSelectSubtitleTrack(it)
            },
            onRefreshAddonSubtitles = {
                resetControlsTimer()
                onRefreshAddonSubtitles()
            },
            onDismiss = onCloseSurface,
        )
    }
}

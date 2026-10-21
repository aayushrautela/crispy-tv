package com.crispy.tv.tv.ui.screens.player

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.crispy.tv.nativeengine.playback.NativeTrack
import com.crispy.tv.tv.player.TvPlayerOverlay
import com.crispy.tv.tv.player.TvPlayerUiState
import com.crispy.tv.tv.player.TvPlayerViewModel
import com.crispy.tv.tv.player.formatPlaybackTime
import kotlinx.coroutines.delay

/**
 * The TV player.
 *
 * ## The keys are the interface
 *
 * There is no pointer here, so **every action is a key**: the centre key plays and
 * pauses, the left and right keys move ten seconds, the down key opens the options
 * sheet, and the back key closes it. The chrome follows the same rule -- it shows
 * itself when a key is pressed and hides itself again a few seconds later, so a
 * viewer who has not touched anything is looking at the picture and nothing else.
 *
 * The sheet is walked with the up and down keys rather than focused row by row,
 * because a list the viewer can only move through one press at a time is a list
 * that can be read without looking at it, and that is what a television wants.
 */
@OptIn(UnstableApi::class)
@Composable
fun TvPlayerScreen(
    viewModel: TvPlayerViewModel,
    onChangeSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var chromeVisible by remember { mutableStateOf(true) }
    val rootFocusRequester = remember { FocusRequester() }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> viewModel.onHostPaused()
                Lifecycle.Event.ON_RESUME -> viewModel.onHostResumed()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The last position is written as the player goes away, on a scope that is not
    // cancelled by the very act of leaving.
    DisposableEffect(Unit) {
        onDispose { viewModel.reportFinalPosition() }
    }

    LaunchedEffect(state.overlay) {
        if (state.overlay == TvPlayerOverlay.NONE) {
            rootFocusRequester.requestFocus()
        }
    }

    // The chrome hides itself, but never while a sheet is open: a sheet the viewer
    // cannot see is a sheet they cannot use.
    LaunchedEffect(chromeVisible, state.overlay, state.isPlaying) {
        if (chromeVisible && state.overlay == TvPlayerOverlay.NONE) {
            delay(ChromeTimeoutMs)
            chromeVisible = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocusRequester)
            .focusable()
            .onKeyEvent { event ->
                handleKey(event, state, viewModel) { chromeVisible = true }
            },
    ) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    viewModel.bindPlayerView(this)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        AnimatedVisibility(visible = chromeVisible || state.hasError || state.preparing) {
            PlayerChrome(state = state)
        }

        if (state.overlay != TvPlayerOverlay.NONE) {
            PlayerSheet(
                state = state,
                viewModel = viewModel,
                onChangeSource = onChangeSource,
            )
        }
    }
}

/**
 * What the keys do when no sheet is open.
 *
 * A failure takes the centre key: the viewer pressing the middle of the pad on an
 * error card means "try again", and making them find a retry button first would be
 * one more thing to read on a screen they cannot enlarge.
 */
private fun handleKey(
    event: KeyEvent,
    state: TvPlayerUiState,
    viewModel: TvPlayerViewModel,
    wakeChrome: () -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when (event.key) {
        Key.DirectionCenter, Key.Enter, Key.Spacebar -> {
            wakeChrome()
            if (state.hasError) {
                viewModel.retry()
            } else {
                viewModel.togglePlayPause()
            }
            true
        }

        Key.DirectionLeft -> {
            wakeChrome()
            viewModel.seekBy(-SeekStepMs)
            true
        }

        Key.DirectionRight -> {
            wakeChrome()
            viewModel.seekBy(SeekStepMs)
            true
        }

        Key.DirectionDown -> {
            wakeChrome()
            viewModel.openOverlay(TvPlayerOverlay.OPTIONS)
            true
        }

        Key.DirectionUp -> {
            wakeChrome()
            true
        }

        else -> false
    }
}

/**
 * The chrome: what is playing, how far in it is, and what the keys do.
 */
@Composable
private fun PlayerChrome(state: TvPlayerUiState) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 48.dp, vertical = 18.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.title?.takeIf { it.isNotBlank() } ?: "Now playing",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                )
                state.episodeLabel?.let { label ->
                    Text(text = label, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                }
            }
            Text(
                text = if (state.isBuffering) "Buffering…" else if (state.isPlaying) "Playing" else "Paused",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 14.sp,
            )
        }
        Spacer(Modifier.weight(1f))
        state.notice?.let { notice ->
            Text(
                text = notice,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 48.dp, bottom = 8.dp),
            )
        }
        when {
            state.hasError -> ErrorCard(message = state.errorMessage.orEmpty())
            state.preparing -> Text(
                text = "Preparing playback…",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 15.sp,
                modifier = Modifier.padding(start = 48.dp, bottom = 32.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        PlayerProgressBar(state = state)
    }
}

/**
 * The progress bar and the clock.
 *
 * The bar is a reading of the engine's snapshot, not a second position kept in
 * step by hand: it is redrawn from the same numbers the clock is drawn from.
 */
@Composable
private fun PlayerProgressBar(state: TvPlayerUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 48.dp, vertical = 16.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.25f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(state.progressFraction)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = formatPlaybackTime(state.positionMs),
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 13.sp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "/ ${formatPlaybackTime(state.durationMs)}",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "◀ ▶ seek · OK play/pause · ▼ options",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 22.dp, vertical = 14.dp),
    ) {
        Text(
            text = if (message.isBlank()) "Playback failed." else message,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 15.sp,
        )
        Spacer(Modifier.width(18.dp))
        Text(
            text = "OK to try again",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 13.sp,
        )
    }
}

/**
 * One line in a sheet.
 */
private data class SheetEntry(
    val label: String,
    val detail: String? = null,
    val onSelect: () -> Unit,
)

/**
 * The sheet: the options, the subtitles, the audio tracks or the speeds.
 *
 * The sheet takes the focus when it opens and gives it back when it closes, so the
 * keys that walk the sheet are the keys the viewer expects them to be.
 */
@Composable
private fun PlayerSheet(
    state: TvPlayerUiState,
    viewModel: TvPlayerViewModel,
    onChangeSource: () -> Unit,
) {
    val entries = remember(state.overlay, state.subtitleTracks, state.audioTracks, state.playbackSpeed, state.muted) {
        sheetEntries(state, viewModel, onChangeSource)
    }
    var selectedIndex by remember(state.overlay) { mutableStateOf(0) }
    val sheetFocusRequester = remember { FocusRequester() }

    LaunchedEffect(state.overlay) {
        sheetFocusRequester.requestFocus()
    }

    BackHandler { viewModel.closeOverlay() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .padding(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xE6, 0x14, 0x18, 0x1C))
                .padding(vertical = 18.dp)
                .focusRequester(sheetFocusRequester)
                .focusable()
                .onKeyEvent { event ->
                    handleSheetKey(event, entries, selectedIndex) { index ->
                        selectedIndex = index
                    }
                },
        ) {
            Text(
                text = sheetTitle(state.overlay),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
            )
            Spacer(Modifier.height(8.dp))
            entries.forEachIndexed { index, entry ->
                SheetRow(entry = entry, selected = index == selectedIndex)
            }
            if (entries.isEmpty()) {
                Text(
                    text = "Nothing to choose here.",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "▲ ▼ choose · OK select · BACK close",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 22.dp),
            )
        }
    }
}

/**
 * The rows a sheet offers.
 *
 * **The sheet never offers what the stream does not have**: a subtitles entry with
 * no subtitles behind it is a dead end, and a dead end on a television is one the
 * viewer has to press back out of.
 */
private fun sheetEntries(
    state: TvPlayerUiState,
    viewModel: TvPlayerViewModel,
    onChangeSource: () -> Unit,
): List<SheetEntry> {
    return when (state.overlay) {
        TvPlayerOverlay.OPTIONS -> buildList {
            if (state.hasSubtitles) {
                add(
                    SheetEntry(
                        label = "Subtitles",
                        detail = state.selectedSubtitleTrackId?.let { id ->
                            state.subtitleTracks.firstOrNull { it.id == id }?.displayName()
                        } ?: "Off",
                        onSelect = { viewModel.openOverlay(TvPlayerOverlay.SUBTITLES) },
                    ),
                )
            }
            if (state.hasAudioTracks) {
                add(
                    SheetEntry(
                        label = "Audio",
                        detail = state.selectedAudioTrackId?.let { id ->
                            state.audioTracks.firstOrNull { it.id == id }?.displayName()
                        },
                        onSelect = { viewModel.openOverlay(TvPlayerOverlay.AUDIO) },
                    ),
                )
            }
            add(
                SheetEntry(
                    label = "Playback speed",
                    detail = "${state.playbackSpeed}x",
                    onSelect = { viewModel.openOverlay(TvPlayerOverlay.SPEED) },
                ),
            )
            add(
                SheetEntry(
                    label = if (state.muted) "Unmute" else "Mute",
                    onSelect = { viewModel.toggleMuted() },
                ),
            )
            add(
                SheetEntry(
                    label = "Change source",
                    onSelect = { onChangeSource() },
                ),
            )
        }

        TvPlayerOverlay.SUBTITLES -> buildList {
            add(
                SheetEntry(
                    label = "Off",
                    detail = if (state.selectedSubtitleTrackId == null) "Selected" else null,
                    onSelect = {
                        viewModel.selectSubtitleTrack(null)
                        viewModel.closeOverlay()
                    },
                ),
            )
            state.subtitleTracks.forEach { track ->
                add(
                    SheetEntry(
                        label = track.displayName(),
                        detail = if (track.id == state.selectedSubtitleTrackId) "Selected" else null,
                        onSelect = {
                            viewModel.selectSubtitleTrack(track.id)
                            viewModel.closeOverlay()
                        },
                    ),
                )
            }
        }

        TvPlayerOverlay.AUDIO -> state.audioTracks.map { track ->
            SheetEntry(
                label = track.displayName(),
                detail = if (track.id == state.selectedAudioTrackId) "Selected" else null,
                onSelect = {
                    viewModel.selectAudioTrack(track.id)
                    viewModel.closeOverlay()
                },
            )
        }

        TvPlayerOverlay.SPEED -> PlaybackSpeeds.map { speed ->
            SheetEntry(
                label = "${speed}x",
                detail = if (state.playbackSpeed == speed) "Selected" else null,
                onSelect = {
                    viewModel.setPlaybackSpeed(speed)
                    viewModel.closeOverlay()
                },
            )
        }

        TvPlayerOverlay.NONE -> emptyList()
    }
}

private fun sheetTitle(overlay: TvPlayerOverlay): String = when (overlay) {
    TvPlayerOverlay.OPTIONS -> "Options"
    TvPlayerOverlay.SUBTITLES -> "Subtitles"
    TvPlayerOverlay.AUDIO -> "Audio"
    TvPlayerOverlay.SPEED -> "Playback speed"
    TvPlayerOverlay.NONE -> ""
}

/**
 * The keys that walk a sheet.
 */
private fun handleSheetKey(
    event: KeyEvent,
    entries: List<SheetEntry>,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (entries.isEmpty()) return false
    return when (event.key) {
        Key.DirectionUp -> {
            val next = (selectedIndex - 1 + entries.size) % entries.size
            onSelectIndex(next)
            true
        }

        Key.DirectionDown -> {
            onSelectIndex((selectedIndex + 1) % entries.size)
            true
        }

        Key.DirectionCenter, Key.Enter, Key.Spacebar -> {
            entries.getOrNull(selectedIndex)?.onSelect?.invoke()
            true
        }

        else -> false
    }
}

@Composable
private fun SheetRow(entry: SheetEntry, selected: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color.White.copy(alpha = 0.16f) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 12.dp),
    ) {
        Text(
            text = entry.label,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.75f),
            fontSize = 15.sp,
        )
        Spacer(Modifier.weight(1f))
        entry.detail?.let { detail ->
            Text(
                text = detail,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
            )
        }
    }
}

/**
 * A track as a viewer reads it.
 */
private fun NativeTrack.displayName(): String {
    val named = title?.trim()?.takeIf { it.isNotEmpty() }
    val language = language?.trim()?.takeIf { it.isNotEmpty() }
    return named ?: language ?: "Track ${index + 1}"
}

private val PlaybackSpeeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

private const val SeekStepMs = 10_000L
private const val ChromeTimeoutMs = 4_000L

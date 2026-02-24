package com.crispy.tv.playerui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.crispy.tv.nativeengine.playback.NativePlaybackEngine
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class PlayerTab {
    None,
    Audio,
    Subtitles,
    Streams,
    Settings,
    Info,
}

@Composable
internal fun PlayerOverlay(
    title: String,
    statusMessage: String,
    errorMessage: String?,
    isBuffering: Boolean,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    stableDurationMs: Long,
    activeEngine: NativePlaybackEngine,
    onBack: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onEngineSelected: (NativePlaybackEngine) -> Unit,
    onRetry: () -> Unit,
) {
    val overlayPadding = rememberOverlayPadding(minPadding = 20.dp)
    val effectiveDurationMs = if (stableDurationMs > 0L) stableDurationMs else durationMs

    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var activeTab by rememberSaveable { mutableStateOf(PlayerTab.None) }
    var controlsResetToken by remember { mutableStateOf(0) }
    var showLoadingCurtain by remember { mutableStateOf(isBuffering) }

    val latestOnBack by rememberUpdatedState(onBack)
    val latestOnTogglePlayPause by rememberUpdatedState(onTogglePlayPause)
    val latestOnSeekTo by rememberUpdatedState(onSeekTo)

    fun resetControlsTimer() {
        controlsResetToken += 1
    }

    fun openTab(tab: PlayerTab) {
        activeTab = tab
        controlsVisible = true
        resetControlsTimer()
    }

    BackHandler(enabled = activeTab != PlayerTab.None) {
        activeTab = PlayerTab.None
    }

    LaunchedEffect(controlsResetToken, controlsVisible, activeTab, isPlaying, isBuffering, errorMessage) {
        if (!controlsVisible) return@LaunchedEffect
        if (activeTab != PlayerTab.None) return@LaunchedEffect
        if (!isPlaying) return@LaunchedEffect
        if (isBuffering) return@LaunchedEffect
        if (errorMessage != null) return@LaunchedEffect

        delay(4_000)
        controlsVisible = false
    }

    LaunchedEffect(isBuffering, errorMessage) {
        if (errorMessage != null) {
            showLoadingCurtain = false
            return@LaunchedEffect
        }

        if (isBuffering) {
            showLoadingCurtain = true
        } else {
            delay(300)
            showLoadingCurtain = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Tap catcher above the video surface.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(activeTab, controlsVisible) {
                        detectTapGestures(
                            onTap = {
                                if (activeTab != PlayerTab.None) {
                                    activeTab = PlayerTab.None
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
                    errorMessage != null -> errorMessage
                    statusMessage.isNotBlank() -> statusMessage
                    else -> "Loading..."
                },
            modifier = Modifier.align(Alignment.Center),
        )

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.30f)),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(overlayPadding),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    PlayerTopBar(
                        title = title,
                        subtitle = statusMessage.takeIf { it.isNotBlank() && it != "Playing" },
                        errorMessage = errorMessage,
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
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(44.dp),
                        )
                    }

                    PlayerBottomControls(
                        positionMs = positionMs,
                        durationMs = effectiveDurationMs,
                        onSeekTo = {
                            resetControlsTimer()
                            latestOnSeekTo(it)
                        },
                        onOpenAudio = { openTab(PlayerTab.Audio) },
                        onOpenSubtitles = { openTab(PlayerTab.Subtitles) },
                        onOpenStreams = { openTab(PlayerTab.Streams) },
                        onOpenSettings = { openTab(PlayerTab.Settings) },
                        onOpenInfo = { openTab(PlayerTab.Info) },
                    )
                }
            }
        }

        PlayerSideSheet(
            tab = activeTab,
            activeEngine = activeEngine,
            onEngineSelected = {
                resetControlsTimer()
                onEngineSelected(it)
            },
            onRetry = {
                resetControlsTimer()
                onRetry()
            },
            onClose = { activeTab = PlayerTab.None },
        )
    }
}

@Composable
private fun PlayerTopBar(
    title: String,
    subtitle: String?,
    errorMessage: String?,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title.ifBlank { "Player" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PlayerBottomControls(
    positionMs: Long,
    durationMs: Long,
    onSeekTo: (Long) -> Unit,
    onOpenAudio: () -> Unit,
    onOpenSubtitles: () -> Unit,
    onOpenStreams: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenInfo: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PlayerSeekBar(
            positionMs = positionMs,
            durationMs = durationMs,
            onSeekTo = onSeekTo,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            PlayerPill {
                Text(
                    text = buildTimePillText(positionMs = positionMs, durationMs = durationMs),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            PlayerPill {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    PlayerTabIconButton(
                        icon = Icons.Outlined.Headphones,
                        contentDescription = "Audio",
                        onClick = onOpenAudio,
                    )
                    PlayerTabIconButton(
                        icon = Icons.Outlined.ClosedCaption,
                        contentDescription = "Subtitles",
                        onClick = onOpenSubtitles,
                    )
                    PlayerTabIconButton(
                        icon = Icons.Outlined.Layers,
                        contentDescription = "Streams",
                        onClick = onOpenStreams,
                    )
                    PlayerTabIconButton(
                        icon = Icons.Outlined.Settings,
                        contentDescription = "Settings",
                        onClick = onOpenSettings,
                    )
                    PlayerTabIconButton(
                        icon = Icons.Outlined.Info,
                        contentDescription = "Info",
                        onClick = onOpenInfo,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerSeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeekTo: (Long) -> Unit,
) {
    val canSeek = durationMs > 0L
    val scope = rememberCoroutineScope()
    var isSeeking by remember { mutableStateOf(false) }
    var seekFraction by remember { mutableStateOf(0f) }

    val currentFraction =
        if (!canSeek) {
            0f
        } else {
            (positionMs.coerceAtLeast(0L).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        }

    val sliderFraction = if (isSeeking) seekFraction else currentFraction

    Slider(
        value = sliderFraction,
        onValueChange = { fraction ->
            if (!canSeek) return@Slider
            isSeeking = true
            seekFraction = fraction.coerceIn(0f, 1f)
            onSeekTo((seekFraction * durationMs).roundToLong())
        },
        enabled = canSeek,
        onValueChangeFinished = {
            if (!canSeek) return@Slider
            // Mirror native overlay behavior: keep "seeking" UI briefly.
            val targetMs = (seekFraction.coerceIn(0f, 1f) * durationMs).roundToLong()
            onSeekTo(targetMs)
            scope.launch {
                delay(500)
                isSeeking = false
            }
        },
        modifier = Modifier.fillMaxWidth(),
        colors =
            SliderDefaults.colors(
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
                thumbColor = MaterialTheme.colorScheme.primary,
            ),
    )
}

@Composable
private fun PlayerPill(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        content()
    }
}

@Composable
private fun PlayerTabIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription)
    }
}

@Composable
private fun PlayerSideSheet(
    tab: PlayerTab,
    activeEngine: NativePlaybackEngine,
    onEngineSelected: (NativePlaybackEngine) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val visible = tab != PlayerTab.None
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val sheetModifier =
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(0.86f)
                .widthIn(max = 360.dp)

        val sheetTitle =
            when (tab) {
                PlayerTab.None -> ""
                PlayerTab.Audio -> "Audio"
                PlayerTab.Subtitles -> "Subtitles"
                PlayerTab.Streams -> "Streams"
                PlayerTab.Settings -> "Settings"
                PlayerTab.Info -> "Info"
            }

        AnimatedVisibility(
            visible = visible,
            enter =
                fadeIn(animationSpec = tween(200)) +
                    slideInHorizontally(animationSpec = tween(200)) { fullWidth -> fullWidth },
            exit =
                fadeOut(animationSpec = tween(180)) +
                    slideOutHorizontally(animationSpec = tween(180)) { fullWidth -> fullWidth },
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f))
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = onClose,
                            ),
                )

                Surface(
                    modifier = sheetModifier,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(text = sheetTitle, style = MaterialTheme.typography.titleLarge)
                            IconButton(onClick = onClose) {
                                Icon(imageVector = Icons.Filled.Close, contentDescription = "Close")
                            }
                        }

                        when (tab) {
                            PlayerTab.Settings -> {
                                Text(text = "Playback Engine", style = MaterialTheme.typography.titleMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = activeEngine == NativePlaybackEngine.EXO,
                                        onClick = { onEngineSelected(NativePlaybackEngine.EXO) },
                                        label = { Text("Exo") },
                                    )
                                    FilterChip(
                                        selected = activeEngine == NativePlaybackEngine.VLC,
                                        onClick = { onEngineSelected(NativePlaybackEngine.VLC) },
                                        label = { Text("VLC") },
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Button(onClick = onRetry) {
                                    Text("Retry playback")
                                }
                            }

                            PlayerTab.Audio,
                            PlayerTab.Subtitles,
                            PlayerTab.Streams,
                            PlayerTab.Info,
                            -> {
                                Text(
                                    text = "UI copied; wiring comes next.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            PlayerTab.None -> Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerLoadingCurtain(
    visible: Boolean,
    text: String,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(200)),
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    text = text.ifBlank { "Loading..." },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun rememberOverlayPadding(minPadding: Dp): PaddingValues {
    val safeDrawing = WindowInsets.safeDrawing
    val safeGestures = WindowInsets.safeGestures
    val density = androidx.compose.ui.platform.LocalDensity.current
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current

    val leftPx = maxOf(
        safeDrawing.getLeft(density, layoutDirection),
        safeGestures.getLeft(density, layoutDirection),
    )
    val rightPx = maxOf(
        safeDrawing.getRight(density, layoutDirection),
        safeGestures.getRight(density, layoutDirection),
    )
    val topPx = maxOf(
        safeDrawing.getTop(density),
        safeGestures.getTop(density),
    )
    val bottomPx = maxOf(
        safeDrawing.getBottom(density),
        safeGestures.getBottom(density),
    )

    val left = with(density) { leftPx.toDp() }
    val right = with(density) { rightPx.toDp() }
    val top = with(density) { topPx.toDp() }
    val bottom = with(density) { bottomPx.toDp() }

    return remember(left, right, top, bottom, minPadding) {
        PaddingValues(
            start = maxOf(minPadding, left),
            end = maxOf(minPadding, right),
            top = maxOf(minPadding, top),
            bottom = maxOf(minPadding, bottom),
        )
    }
}

private fun buildTimePillText(positionMs: Long, durationMs: Long): String {
    val left = formatPlaybackTimeMs(positionMs)
    val right = if (durationMs > 0L) formatPlaybackTimeMs(durationMs) else "--:--"
    return "$left / $right"
}

private fun formatPlaybackTimeMs(timeMs: Long): String {
    val totalSeconds = (timeMs.coerceAtLeast(0L) / 1000L).toInt()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

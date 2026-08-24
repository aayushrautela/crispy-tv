package com.crispy.tv.playerui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.crispy.tv.details.DetailsPaletteColors
import com.crispy.tv.addons.model.MediaDetails
import com.crispy.tv.ui.components.skeletonElement
import com.crispy.tv.addons.model.MediaVideo
import com.crispy.tv.nativeengine.playback.PlayerResizeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

@Composable
internal fun PlayerTopBar(
    title: String,
    subtitle: String?,
    errorMessage: String?,
    palette: DetailsPaletteColors,
    isMetadataLoaded: Boolean,
    onBack: () -> Unit,
    onShowInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(shape = CircleShape, color = palette.pillBackground, contentColor = palette.onPillBackground) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            if (!isMetadataLoaded && title.isBlank()) {
                PlayerTextSkeleton(width = 200.dp, height = 16.dp, palette = palette)
                Spacer(modifier = Modifier.height(6.dp))
                PlayerTextSkeleton(width = 140.dp, height = 12.dp, palette = palette)
            } else {
                Text(
                    text = title.ifBlank { "Player" },
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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

        IconButton(
            onClick = onShowInfo,
            enabled = isMetadataLoaded,
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = "Info",
                tint = if (isMetadataLoaded) palette.onPillBackground else palette.onPillBackground.copy(alpha = 0.4f),
            )
        }
    }
}

@Composable
private fun PlayerTextSkeleton(
    width: Dp,
    height: Dp,
    palette: DetailsPaletteColors,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .width(width)
                .height(height)
                .skeletonElement(
                    shape = RoundedCornerShape(height / 2),
                    color = palette.pillBackground,
                ),
    )
}

@Composable
internal fun PlayerBottomControls(
    positionMsState: State<Long>,
    durationMs: Long,
    hasAudioTracks: Boolean,
    palette: DetailsPaletteColors,
    onSeekTo: (Long) -> Unit,
    onOpenStreams: () -> Unit,
    onOpenAudio: () -> Unit,
    onOpenSubtitles: () -> Unit,
    onCycleResizeMode: () -> Unit,
    resizeMode: PlayerResizeMode,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PlayerSeekBar(
            positionMsState = positionMsState,
            durationMs = durationMs,
            palette = palette,
            onSeekTo = onSeekTo,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            PlayerPill(
                containerColor = palette.pillBackground,
                contentColor = palette.onPillBackground,
            ) {
                // Reading the position State here scopes per-tick recomposition to this
                // text alone instead of the whole controls column.
                val positionMs = positionMsState.value
                Text(
                    text = buildTimePillText(positionMs = positionMs, durationMs = durationMs),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            PlayerPill(
                containerColor = palette.pillBackground,
                contentColor = palette.onPillBackground,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .horizontalScroll(rememberScrollState()),
                ) {
                    if (hasAudioTracks) {
                        IconButton(
                            onClick = onOpenAudio,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.GraphicEq,
                                contentDescription = "Audio tracks",
                                tint = palette.onPillBackground,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    IconButton(
                        onClick = onOpenSubtitles,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Subtitles,
                            contentDescription = "Subtitles",
                            tint = palette.onPillBackground,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        onClick = onOpenStreams,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Layers,
                            contentDescription = "Streams",
                            tint = palette.onPillBackground,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        onClick = onCycleResizeMode,
                        modifier = Modifier.size(40.dp),
                    ) {
                        if (resizeMode == PlayerResizeMode.Zoom) {
                            Icon(
                                painter = painterResource(com.crispy.tv.R.drawable.ic_player_aspect_ratio),
                                contentDescription = "Resize: ${resizeMode.label}",
                                tint = palette.onPillBackground,
                                modifier = Modifier.size(20.dp),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Crop,
                                contentDescription = "Resize: ${resizeMode.label}",
                                tint = palette.onPillBackground,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerSeekBar(
    positionMsState: State<Long>,
    durationMs: Long,
    palette: DetailsPaletteColors,
    onSeekTo: (Long) -> Unit,
) {
    // Only this composable reads the hot position; ticks never recompose parents.
    val positionMs = positionMsState.value
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
                activeTrackColor = palette.accent,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                thumbColor = palette.accent,
            ),
    )
}

@Composable
internal fun PlayerPill(
    containerColor: Color,
    contentColor: Color,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        content()
    }
}

@Composable
internal fun PlayerErrorCard(
    errorMessage: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = errorMessage != null,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(200)),
        modifier = modifier,
    ) {
        ElevatedCard {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Playback problem",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = errorMessage.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRetry) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}

internal fun buildTimePillText(positionMs: Long, durationMs: Long): String {
    val left = formatPlaybackTimeMs(positionMs)
    val right = if (durationMs > 0L) formatPlaybackTimeMs(durationMs) else "--:--"
    return "$left / $right"
}

internal fun formatPlaybackTimeMs(timeMs: Long): String {
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

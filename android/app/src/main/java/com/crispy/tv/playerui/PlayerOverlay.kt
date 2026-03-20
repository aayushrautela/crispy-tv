@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.crispy.tv.playerui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.crispy.tv.details.DetailsPaletteColors
import com.crispy.tv.details.StreamProviderUiState
import com.crispy.tv.details.StreamSelectorUiState
import com.crispy.tv.home.MediaDetails
import com.crispy.tv.home.MediaVideo
import com.crispy.tv.streams.AddonStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    onOpenStreams: () -> Unit,
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
            PlayerPill(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Text(
                    text = buildTimePillText(positionMs = positionMs, durationMs = durationMs),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            PlayerPill(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    PlayerActionButton(
                        icon = Icons.Outlined.Layers,
                        label = "Streams",
                        onClick = onOpenStreams,
                    )
                    PlayerActionButton(
                        icon = Icons.Outlined.Info,
                        label = "Info",
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
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                thumbColor = MaterialTheme.colorScheme.primary,
            ),
    )
}

@Composable
private fun PlayerPill(
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
private fun PlayerActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Button(onClick = onClick) {
        Icon(imageVector = icon, contentDescription = label)
        Spacer(modifier = Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun PlayerErrorCard(
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

@Composable
private fun PlayerInfoSheet(
    visible: Boolean,
    details: MediaDetails?,
    seasons: List<Int>,
    selectedSeason: Int?,
    seasonEpisodes: List<MediaVideo>,
    currentEpisodeId: String?,
    episodesIsLoading: Boolean,
    episodesStatusMessage: String,
    palette: DetailsPaletteColors,
    onClose: () -> Unit,
    onSeasonSelected: (Int) -> Unit,
    onEpisodeSelected: (String) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(200)) + slideInHorizontally(animationSpec = tween(200)) { fullWidth -> fullWidth },
            exit = fadeOut(animationSpec = tween(180)) + slideOutHorizontally(animationSpec = tween(180)) { fullWidth -> fullWidth },
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = onClose,
                            ),
                )

                Surface(
                    modifier =
                        Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .fillMaxWidth(0.88f)
                            .widthIn(max = 400.dp),
                    color = palette.pageBackground,
                    contentColor = palette.onPageBackground,
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Info", style = MaterialTheme.typography.titleLarge)
                            IconButton(onClick = onClose) {
                                Icon(imageVector = Icons.Filled.Close, contentDescription = "Close")
                            }
                        }

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            item {
                                PlayerInfoHeader(details = details)
                            }

                            details?.description?.trim()?.takeIf { it.isNotBlank() }?.let { description ->
                                item {
                                    ElevatedCard {
                                        Column(
                                            modifier = Modifier.padding(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Text("Overview", style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                text = description,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }

                            if (seasons.isNotEmpty()) {
                                item {
                                    Text("Seasons", style = MaterialTheme.typography.titleMedium)
                                }
                                item {
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        seasons.forEach { season ->
                                            FilterChip(
                                                selected = season == selectedSeason,
                                                onClick = { onSeasonSelected(season) },
                                                label = { Text("Season $season") },
                                            )
                                        }
                                    }
                                }
                            }

                            if (episodesIsLoading) {
                                item {
                                    ElevatedCard {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                                        }
                                    }
                                }
                            } else if (seasonEpisodes.isNotEmpty()) {
                                item {
                                    Text("Episodes", style = MaterialTheme.typography.titleMedium)
                                }
                                items(items = seasonEpisodes, key = { episode -> episode.id }) { episode ->
                                    EpisodeRow(
                                        episode = episode,
                                        isCurrent = episode.id.equals(currentEpisodeId, ignoreCase = true),
                                        onClick = { onEpisodeSelected(episode.id) },
                                    )
                                }
                            } else if (episodesStatusMessage.isNotBlank()) {
                                item {
                                    ElevatedCard {
                                        Text(
                                            text = episodesStatusMessage,
                                            modifier = Modifier.padding(16.dp),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerInfoHeader(details: MediaDetails?) {
    if (details == null) {
        ElevatedCard {
            Text(
                text = "Loading title details...",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    val imageUrl = details.backdropUrl?.trim()?.takeIf { it.isNotBlank() } ?: details.posterUrl?.trim()?.takeIf { it.isNotBlank() }
    val metadata = buildHeaderMetadata(details)

    ElevatedCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                if (imageUrl != null) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(168.dp),
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(168.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.BrokenImage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Text(
                text = details.title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (metadata.isNotEmpty()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    metadata.forEach { item ->
                        StaticTag(text = item)
                    }
                }
            }

            if (details.genres.isNotEmpty()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    details.genres.forEach { genre ->
                        StaticTag(text = genre)
                    }
                }
            }

            details.cast.takeIf { it.isNotEmpty() }?.let { cast ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Cast", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = cast.joinToString(separator = " • "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun buildHeaderMetadata(details: MediaDetails): List<String> {
    return buildList {
        details.year?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
        details.runtime?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
        details.certification?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
        details.rating?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
    }
}

@Composable
private fun EpisodeRow(
    episode: MediaVideo,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    ElevatedCard(onClick = onClick) {
        ListItem(
            headlineContent = {
                Text(
                    text = episode.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else null,
                )
            },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    episodeRowMeta(episode)?.let { text ->
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    episode.overview?.trim()?.takeIf { it.isNotBlank() }?.let { overview ->
                        Text(
                            text = overview,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            trailingContent =
                if (isCurrent) {
                    {
                        StaticTag(
                            text = "Now Playing",
                            emphasized = true,
                        )
                    }
                } else {
                    null
                },
        )
    }
}

private fun episodeRowMeta(episode: MediaVideo): String? {
    val parts = mutableListOf<String>()
    val season = episode.season
    val episodeNumber = episode.episode
    if (season != null && episodeNumber != null) {
        parts += "S$season E$episodeNumber"
    }
    formatEpisodeDate(episode.released)?.let(parts::add)
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
}

@Composable
private fun PlayerStreamsSheet(
    visible: Boolean,
    details: MediaDetails?,
    state: StreamSelectorUiState,
    onDismiss: () -> Unit,
    onProviderSelected: (String?) -> Unit,
    onRetryProvider: (String) -> Unit,
    onStreamSelected: (AddonStream) -> Unit,
) {
    if (!visible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val filteredProviders =
        remember(state.providers, state.selectedProviderId) {
            val selectedProvider = state.selectedProviderId
            if (selectedProvider.isNullOrBlank()) {
                state.providers
            } else {
                state.providers.filter { provider ->
                    provider.providerId.equals(selectedProvider, ignoreCase = true)
                }
            }
        }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        scrimColor = Color.Transparent,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    StreamSheetHeader(details = details, episode = state.headerEpisode)
                }

                item {
                    ProviderChipsRow(
                        state = state,
                        onProviderSelected = onProviderSelected,
                    )
                }

                if (
                    !state.isLoading &&
                    filteredProviders.all { provider -> provider.streams.isEmpty() && provider.errorMessage == null }
                ) {
                    item {
                        ElevatedCard {
                            Text(
                                text = "No streams found for this title.",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                filteredProviders.forEach { provider ->
                    if (provider.errorMessage != null) {
                        item(key = "provider_error_${provider.providerId}") {
                            ProviderErrorRow(
                                provider = provider,
                                onRetry = onRetryProvider,
                            )
                        }
                    }

                    if (provider.streams.isNotEmpty()) {
                        items(items = provider.streams, key = { stream -> stream.stableKey }) { stream ->
                            StreamRow(
                                stream = stream,
                                providerName = provider.providerName,
                                onClick = { onStreamSelected(stream) },
                            )
                        }
                    }
                }

                if (state.isLoading) {
                    item {
                        LoadingMoreStreamsRow()
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamSheetHeader(
    details: MediaDetails?,
    episode: MediaVideo?,
) {
    if (details == null && episode == null) return

    val imageUrl =
        episode?.thumbnailUrl?.trim()?.takeIf { it.isNotBlank() }
            ?: details?.backdropUrl
            ?: details?.posterUrl
    val description =
        episode?.overview?.trim()?.takeIf { it.isNotBlank() }
            ?: details?.description?.trim()?.takeIf { it.isNotBlank() }

    ElevatedCard {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(width = 96.dp, height = 56.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val title = episode?.title?.trim()?.takeIf { it.isNotBlank() } ?: details?.title.orEmpty()
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val metadata = episodeHeaderMetadata(episode = episode, details = details)
                    if (metadata != null) {
                        Text(
                            text = metadata,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (episode != null) {
                        details?.title?.trim()?.takeIf { it.isNotBlank() }?.let { showTitle ->
                            Text(
                                text = showTitle,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            description?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun episodeHeaderMetadata(
    episode: MediaVideo?,
    details: MediaDetails?,
): String? {
    if (episode == null) {
        return details?.year?.trim()?.takeIf { it.isNotBlank() }
    }

    val parts = mutableListOf<String>()
    val season = episode.season
    val episodeNumber = episode.episode
    if (season != null && episodeNumber != null) {
        parts += "S$season E$episodeNumber"
    }
    formatEpisodeDate(episode.released)?.let(parts::add)
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
}

@Composable
private fun ProviderChipsRow(
    state: StreamSelectorUiState,
    onProviderSelected: (String?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = state.selectedProviderId == null,
            onClick = { onProviderSelected(null) },
            label = { Text("All ${state.totalStreamCount}") },
        )

        state.providers.forEach { provider ->
            FilterChip(
                selected = provider.providerId.equals(state.selectedProviderId, ignoreCase = true),
                onClick = { onProviderSelected(provider.providerId) },
                label = { Text("${provider.providerName} ${provider.streams.size}") },
            )
        }
    }
}

@Composable
private fun ProviderErrorRow(
    provider: StreamProviderUiState,
    onRetry: (String) -> Unit,
) {
    ElevatedCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${provider.providerName}: ${provider.errorMessage}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = { onRetry(provider.providerId) }) {
                Text("Retry")
            }
        }
    }
}

@Composable
private fun LoadingMoreStreamsRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun StaticTag(
    text: String,
    emphasized: Boolean = false,
) {
    val containerColor =
        if (emphasized) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        }
    val contentColor =
        if (emphasized) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun StreamRow(
    stream: AddonStream,
    providerName: String,
    onClick: () -> Unit,
) {
    val detailsText =
        remember(stream.title, stream.description) {
            val title = stream.title?.trim()?.takeIf { it.isNotBlank() }
            val description = stream.description?.trim()?.takeIf { it.isNotBlank() }
            if (description != null && description.contains('\n') && description.length > (title?.length ?: 0)) {
                description
            } else {
                title ?: description
            }
        }

    ElevatedCard(onClick = onClick) {
        ListItem(
            headlineContent = {
                Text(
                    text = stream.name ?: providerName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    detailsText?.let { text ->
                        Text(text = text)
                    }
                    Text(
                        text = providerName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            trailingContent = if (stream.cached) {
                {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = "Cached",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            } else {
                null
            },
        )
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
            color = MaterialTheme.colorScheme.surface,
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
    val density = LocalDensity.current
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

private fun formatEpisodeDate(date: String?): String? {
    val raw = date?.trim().orEmpty()
    if (raw.isBlank()) return null
    val iso = if (raw.length >= 10) raw.take(10) else raw
    return try {
        val parsed = LocalDate.parse(iso)
        parsed.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))
    } catch (_: Throwable) {
        raw
    }
}

package com.crispy.tv.playerui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.crispy.tv.details.DetailsPaletteColors
import com.crispy.tv.home.MediaDetails
import com.crispy.tv.home.MediaVideo
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlayerInfoSheet(
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
                            .fillMaxWidth(0.5f)
                            .widthIn(max = 460.dp),
                    color = palette.pageBackground,
                    contentColor = palette.onPageBackground,
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        PlayerInfoHeader(
                            details = details,
                            palette = palette,
                            onClose = onClose,
                        )

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            details?.description?.trim()?.takeIf { it.isNotBlank() }?.let { description ->
                                item {
                                    ElevatedCard(
                                        colors =
                                            CardDefaults.elevatedCardColors(
                                                containerColor = palette.pillBackground,
                                                contentColor = palette.onPillBackground,
                                            ),
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Text(
                                                "Overview",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = palette.accent,
                                            )
                                            Text(
                                                text = description,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = palette.onPillBackground.copy(alpha = 0.8f),
                                            )
                                        }
                                    }
                                }
                            }

                            if (seasons.isNotEmpty()) {
                                item {
                                    Text(
                                        "Seasons",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = palette.accent,
                                    )
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
                                                shape = RoundedCornerShape(16.dp),
                                                border = null,
                                                colors =
                                                    FilterChipDefaults.filterChipColors(
                                                        containerColor = palette.pillBackground,
                                                        labelColor = palette.onPillBackground,
                                                        selectedContainerColor = palette.accent,
                                                        selectedLabelColor = palette.onAccent,
                                                    ),
                                            )
                                        }
                                    }
                                }
                            }

                            if (episodesIsLoading) {
                                item {
                                    ElevatedCard(
                                        colors =
                                            CardDefaults.elevatedCardColors(
                                                containerColor = palette.pillBackground,
                                                contentColor = palette.onPillBackground,
                                            ),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            LoadingIndicator(modifier = Modifier.size(32.dp))
                                        }
                                    }
                                }
                            } else if (seasonEpisodes.isNotEmpty()) {
                                item {
                                    Text(
                                        "Episodes",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = palette.accent,
                                    )
                                }
                                items(items = seasonEpisodes, key = { episode -> episode.id }) { episode ->
                                    EpisodeRow(
                                        episode = episode,
                                        isCurrent = episode.id.equals(currentEpisodeId, ignoreCase = true),
                                        palette = palette,
                                        onClick = { onEpisodeSelected(episode.id) },
                                    )
                                }
                            } else if (episodesStatusMessage.isNotBlank()) {
                                item {
                                    ElevatedCard(
                                        colors =
                                            CardDefaults.elevatedCardColors(
                                                containerColor = palette.pillBackground,
                                                contentColor = palette.onPillBackground,
                                            ),
                                    ) {
                                        Text(
                                            text = episodesStatusMessage,
                                            modifier = Modifier.padding(16.dp),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = palette.onPillBackground.copy(alpha = 0.8f),
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
private fun PlayerInfoHeader(
    details: MediaDetails?,
    palette: DetailsPaletteColors,
    onClose: () -> Unit,
) {
    if (details == null) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(palette.pageBackground)
                    .padding(16.dp),
        ) {
            Text(
                text = "Loading title details...",
                color = palette.onPageBackground.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    val imageUrl =
        details.backdropUrl?.trim()?.takeIf { it.isNotBlank() }
            ?: details.posterUrl?.trim()?.takeIf { it.isNotBlank() }
    val metadata = buildHeaderMetadata(details)

    Box(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (imageUrl != null) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                ) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colorStops =
                                            arrayOf(
                                                0f to Color.Transparent,
                                                0.55f to Color.Transparent,
                                                1f to palette.pageBackground,
                                            ),
                                    ),
                                ),
                    )
                }
            } else {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .background(palette.pageBackground),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.BrokenImage,
                        contentDescription = null,
                        tint = palette.onPageBackground.copy(alpha = 0.7f),
                    )
                }
            }

            Column(
                modifier = Modifier.padding(horizontal = 20.dp).padding(top = 14.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = details.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = palette.onPageBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (metadata.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        metadata.forEach { item ->
                            StaticTag(text = item, palette = palette)
                        }
                    }
                }

                if (details.genres.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        details.genres.forEach { genre ->
                            StaticTag(text = genre, palette = palette)
                        }
                    }
                }

                details.cast.takeIf { it.isNotEmpty() }?.let { cast ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Cast",
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.accent,
                        )
                        Text(
                            text = cast.joinToString(separator = " • "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.onPageBackground.copy(alpha = 0.8f),
                        )
                    }
                }
            }
        }

        IconButton(
            onClick = onClose,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(palette.pageBackground.copy(alpha = 0.55f), CircleShape)
                    .padding(4.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close",
                tint = palette.onPageBackground,
            )
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
    palette: DetailsPaletteColors,
    onClick: () -> Unit,
) {
    ElevatedCard(
        onClick = onClick,
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = palette.pillBackground,
                contentColor = palette.onPillBackground,
            ),
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = episode.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else null,
                    color = if (isCurrent) palette.accent else palette.onPillBackground,
                )
            },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    episodeRowMeta(episode)?.let { text ->
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.onPillBackground.copy(alpha = 0.7f),
                        )
                    }
                    episode.overview?.trim()?.takeIf { it.isNotBlank() }?.let { overview ->
                        Text(
                            text = overview,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            color = palette.onPillBackground.copy(alpha = 0.8f),
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
                            palette = palette,
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

internal fun formatEpisodeDate(date: String?): String? {
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

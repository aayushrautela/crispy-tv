package com.crispy.tv.playerui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.crispy.tv.details.DetailsPaletteColors
import com.crispy.tv.home.MediaDetails
import com.crispy.tv.home.MediaVideo
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

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

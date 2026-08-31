package com.crispy.tv.playerui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.crispy.tv.addons.model.MediaVideo
import com.crispy.tv.details.DetailsPaletteColors
import com.crispy.tv.details.EpisodeCard
import com.crispy.tv.details.EpisodeCardSkeleton
import com.crispy.tv.details.EpisodeWatchState
import com.crispy.tv.ui.theme.Dimensions

@Composable
internal fun PlayerEpisodesSheet(
    visible: Boolean,
    seasons: List<Int>,
    selectedSeason: Int?,
    seasonEpisodes: List<MediaVideo>,
    episodesIsLoading: Boolean,
    episodesStatusMessage: String,
    palette: DetailsPaletteColors,
    activeSeason: Int?,
    activeEpisode: Int?,
    onSeasonSelected: (Int) -> Unit,
    onEpisodeSelected: (String) -> Unit,
    onClose: () -> Unit,
) {
    // Hide entirely for movies (no seasons)
    if (seasons.isEmpty() && !episodesIsLoading) return

    PlayerBottomSheet(
        visible = visible,
        palette = palette,
        title = "Episodes",
        onClose = onClose,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (seasons.isNotEmpty()) {
                val selected = selectedSeason ?: seasons.firstOrNull()
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(seasons, key = { it }) { season ->
                        FilterChip(
                            selected = season == selected,
                            onClick = { onSeasonSelected(season) },
                            label = { Text("Season $season") },
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

            val episodes =
                remember(seasonEpisodes) {
                    seasonEpisodes
                        .sortedWith(compareBy<MediaVideo> { it.episode ?: Int.MAX_VALUE }.thenBy { it.title })
                        .take(50)
                }

            when {
                episodesIsLoading && episodes.isEmpty() -> {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(4) {
                            EpisodeCardSkeleton(modifier = Modifier.width(Dimensions.WideCardWidth))
                        }
                    }
                }

                episodesStatusMessage.isNotBlank() && episodes.isEmpty() -> {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 24.dp),
                    ) {
                        Text(
                            text = episodesStatusMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.onPageBackground.copy(alpha = 0.7f),
                        )
                    }
                }

                episodes.isNotEmpty() -> {
                    val currentIndex =
                        remember(episodes, activeSeason, activeEpisode, selectedSeason) {
                            val selected = selectedSeason ?: seasons.firstOrNull()
                            if (selected != activeSeason) -1
                            else episodes.indexOfFirst { it.episode == activeEpisode }
                        }
                    val listState =
                        remember(currentIndex) {
                            androidx.compose.foundation.lazy.LazyListState(
                                firstVisibleItemIndex = currentIndex.coerceAtLeast(0),
                                firstVisibleItemScrollOffset = 0,
                            )
                        }

                    LazyRow(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(episodes, key = { it.id }) { video ->
                            val isActive =
                                video.season == activeSeason && video.episode == activeEpisode
                            EpisodeCard(
                                video = video,
                                watchState = EpisodeWatchState(),
                                isHighlighted = isActive,
                                modifier = Modifier.width(Dimensions.WideCardWidth),
                                onClick = { onEpisodeSelected(video.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

package com.crispy.tv.playerui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.crispy.tv.addons.model.MediaVideo
import com.crispy.tv.details.DetailsPaletteColors
import com.crispy.tv.details.EpisodeCard
import com.crispy.tv.details.EpisodeCardSkeleton
import com.crispy.tv.ui.resources.Res
import com.crispy.tv.ui.resources.ic_close_filled
import com.crispy.tv.ui.theme.Dimensions
import org.jetbrains.compose.resources.painterResource

/**
 * The season chip that should read as selected.
 *
 * A caller that has never chosen lands on the first season rather than on nothing,
 * which is why this is a named function rather than a `?:` at the call site: the
 * fallback is a decision, and the sheet consults it from two places, one of them
 * only when the chip row has already been scrolled (`if (selected != activeSeason) -1`
 * on the LazyRow's initial scroll index). An empty list answers null, which the
 * caller guards with `seasons.isNotEmpty()` before asking.
 */
internal fun selectedSeasonOrFirst(seasons: List<Int>, selectedSeason: Int?): Int? =
    selectedSeason ?: seasons.firstOrNull()

/**
 * The episodes the sheet lists: ordered by number, then by title, then capped.
 *
 * Extracted from a `remember` block that held three decisions inline and none of
 * them were reachable from a test. The order matters more than it looks:
 *
 * - **`episode == null` sorts last, not first.** `?: Int.MAX_VALUE` puts an episode
 *   with no number after every numbered one, so a specials row does not open the
 *   list. A `?: 0` would put it first.
 * - **Title breaks ties**, so two rows with no number -- two specials -- have a
 *   stable order instead of whatever the backend sent.
 * - **The cap is 50 and it is applied last**, after the sort, so a season with 80
 *   episodes shows numbers 1..50 rather than an arbitrary 50.
 */
internal fun visibleEpisodes(seasonEpisodes: List<MediaVideo>): List<MediaVideo> =
    seasonEpisodes
        .sortedWith(compareBy<MediaVideo> { it.episode ?: Int.MAX_VALUE }.thenBy { it.title })
        .take(50)

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
    if (seasons.isEmpty() && !episodesIsLoading) return

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(180)),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.75f))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = onClose,
                        ),
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_close_filled),
                        contentDescription = "Close",
                        tint = Color.White,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(200)) + slideInVertically(animationSpec = tween(220)) { it },
            exit = fadeOut(animationSpec = tween(180)) + slideOutVertically(animationSpec = tween(180)) { it },
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (seasons.isNotEmpty()) {
                    val selected = selectedSeasonOrFirst(seasons, selectedSeason)
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
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
                        visibleEpisodes(seasonEpisodes)
                    }

                when {
                    episodesIsLoading && episodes.isEmpty() -> {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(4) {
                                EpisodeCardSkeleton(modifier = Modifier.width(Dimensions.WideCardWidth))
                            }
                        }
                    }

                    episodes.isNotEmpty() -> {
                        val currentIndex =
                            remember(episodes, activeSeason, activeEpisode, selectedSeason) {
                                val selected = selectedSeasonOrFirst(seasons, selectedSeason)
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
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(episodes, key = { it.id }) { video ->
                                val isActive =
                                    video.season == activeSeason && video.episode == activeEpisode
                                EpisodeCard(
                                    video = video,
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
}

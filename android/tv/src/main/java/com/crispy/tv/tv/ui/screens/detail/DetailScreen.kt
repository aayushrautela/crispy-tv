package com.crispy.tv.tv.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.crispy.tv.tv.ui.components.RailSection

private val ScreenPadding = 48.dp

@Composable
fun DetailScreen(
    state: DetailUiState,
    onSelectSeason: (Int) -> Unit,
    onOpenItem: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.error != null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(state.error, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> DetailContent(
            state = state,
            onSelectSeason = onSelectSeason,
            onOpenItem = onOpenItem,
            modifier = modifier,
        )
    }
}

@Composable
private fun DetailContent(
    state: DetailUiState,
    onSelectSeason: (Int) -> Unit,
    onOpenItem: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroll),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            AsyncImage(
                model = state.backdropUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
                                MaterialTheme.colorScheme.background,
                            ),
                        ),
                    ),
            )
            Column(modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = ScreenPadding)) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.subtitleMeta != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = state.subtitleMeta,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(14.dp))
                PlayButton(enabled = true, label = "Play", onClick = {})
            }
        }

        Spacer(Modifier.height(20.dp))

        state.overview?.let { overview ->
            Text(
                text = overview,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = ScreenPadding),
            )
        }

        if (state.genres.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = ScreenPadding),
            ) {
                state.genres.take(5).forEach { genre ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    ) {
                        Text(genre, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (state.seasons.size > 1 || state.episodes.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            SeasonChips(
                seasons = state.seasons.map { it.seasonNumber },
                selected = state.selectedSeason,
                fallbackSeasonCount = state.seasonCount,
                onSelect = onSelectSeason,
            )
            EpisodeList(
                episodes = state.episodes,
                loading = state.episodesLoading,
            )
        }

        if (state.cast.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            CastRail(cast = state.cast)
        }

        if (state.similar.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            RailSection(
                title = "More Like This",
                items = state.similar,
                onItemClick = { onOpenItem(it.id) },
            )
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun PlayButton(
    enabled: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 34.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            color = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 17.sp,
        )
    }
}

@Composable
private fun SeasonChips(
    seasons: List<Int>,
    selected: Int?,
    fallbackSeasonCount: Int?,
    onSelect: (Int) -> Unit,
) {
    val resolved = seasons.ifEmpty { (1..(fallbackSeasonCount ?: 1)).toList() }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = ScreenPadding),
    ) {
        items(resolved, key = { it }) { season ->
            var focused by remember { mutableStateOf(false) }
            val isSelected = season == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .onFocusChanged { focused = it.isFocused }
                    .clickable { onSelect(season) }
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        when {
                            isSelected -> MaterialTheme.colorScheme.primary
                            focused -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                    )
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            ) {
                Text(
                    text = "Season $season",
                    fontSize = 14.sp,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun EpisodeList(
    episodes: List<DetailEpisodeUi>,
    loading: Boolean,
) {
    Spacer(Modifier.height(16.dp))
    when {
        loading -> Text(
            text = "Loading episodes…",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
        episodes.isEmpty() -> {}
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            episodes.forEach { episode ->
                EpisodeRow(episode = episode)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun EpisodeRow(episode: DetailEpisodeUi) {
    var focused by remember { mutableStateOf(false) }
    val background = if (focused) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    } else {
        Color.Transparent
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding - 12.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable { }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(96.dp)
                .height(54.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Text(
                text = "E${episode.episodeNumber ?: ""}",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = episode.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(episode.airDate, episode.runtimeMinutes?.let { "${it}m" })
                .joinToString(" · ")
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = meta,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CastRail(cast: List<String>) {
    Column {
        Text(
            text = "Cast",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = ScreenPadding),
        ) {
            items(cast, key = { it }) { name ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    Text(name, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

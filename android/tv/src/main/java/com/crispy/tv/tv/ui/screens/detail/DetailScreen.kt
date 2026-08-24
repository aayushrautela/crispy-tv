package com.crispy.tv.tv.ui.screens.detail

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.crispy.tv.tv.ui.components.CrispyLandscapeCard
import com.crispy.tv.tv.ui.components.RailSection
import com.crispy.tv.tv.ui.components.skeletonElement
import com.crispy.tv.tv.ui.theme.rememberDetailsSeedColor
import com.crispy.tv.tv.ui.theme.rememberDetailsTvColorScheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Star

private val ScreenPadding = 48.dp

@Composable
fun DetailScreen(
    state: DetailUiState,
    onSelectSeason: (Int) -> Unit,
    onOpenItem: (String) -> Unit,
    onBack: () -> Unit,
    onPlay: (String) -> Unit,
    onToggleWatchlist: () -> Unit = {},
    onToggleWatched: () -> Unit = {},
    onToggleEpisodeWatched: (DetailEpisodeUi) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val seed by rememberDetailsSeedColor(
        imageUrl = if (state.loading) null else state.backdropUrl,
        fallbackSeed = MaterialTheme.colorScheme.primary,
    )
    val themedScheme = rememberDetailsTvColorScheme(seed)
    androidx.tv.material3.MaterialTheme(colorScheme = themedScheme) {
        when {
            state.loading -> TvDetailSkeleton(modifier = modifier)
            state.error != null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(state.error!!, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> DetailContent(
                state = state,
                onSelectSeason = onSelectSeason,
                onOpenItem = onOpenItem,
                onPlay = onPlay,
                onToggleWatchlist = onToggleWatchlist,
                onToggleWatched = onToggleWatched,
                onToggleEpisodeWatched = onToggleEpisodeWatched,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun TvDetailSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .skeletonElement(shape = RoundedCornerShape(0.dp)),
        )
        Spacer(Modifier.height(20.dp))
        Box(
            modifier = Modifier
                .padding(horizontal = ScreenPadding)
                .fillMaxWidth(0.55f)
                .height(22.dp)
                .skeletonElement(),
        )
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .padding(horizontal = ScreenPadding)
                .fillMaxWidth(0.85f)
                .height(14.dp)
                .skeletonElement(),
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(horizontal = ScreenPadding)) {
            repeat(4) {
                Box(
                    modifier = Modifier
                        .width(220.dp)
                        .height(124.dp)
                        .skeletonElement(),
                )
            }
        }
    }
}

@Composable
private fun DetailContent(
    state: DetailUiState,
    onSelectSeason: (Int) -> Unit,
    onOpenItem: (String) -> Unit,
    onPlay: (String) -> Unit,
    onToggleWatchlist: () -> Unit,
    onToggleWatched: () -> Unit,
    onToggleEpisodeWatched: (DetailEpisodeUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    var aiSlideExpanded by remember { mutableStateOf<AiSlideUi?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        aiSlideExpanded?.let { slide ->
            AiInsightOverlay(slide = slide, onDismiss = { aiSlideExpanded = null })
        }

        Column(
            modifier = Modifier
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
                    .height(360.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
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
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = ScreenPadding),
            ) {
                if (state.logoUrl != null) {
                    AsyncImage(
                        model = state.logoUrl,
                        contentDescription = state.title,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart,
                        modifier = Modifier
                            .width(320.dp)
                            .height(88.dp),
                    )
                } else {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.subtitleMeta != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = state.subtitleMeta!!,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.ratingBadges.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.ratingBadges.forEach { badge ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                Text(badge, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmartPlayButton(
                        label = state.ctaLabel,
                        remainingMinutes = state.ctaRemainingMinutes,
                        onClick = { onPlay(state.itemId) },
                    )
                    HeaderActionButton(
                        label = if (state.isInWatchlist) "In watchlist" else "Watchlist",
                        icon = Icons.Filled.Add,
                        active = state.isInWatchlist,
                        onClick = onToggleWatchlist,
                    )
                    HeaderActionButton(
                        label = if (state.isWatched) "Watched" else "Mark watched",
                        icon = Icons.Filled.Check,
                        active = state.isWatched,
                        onClick = onToggleWatched,
                    )
                    if (state.userRating != null) {
                        HeaderActionButton(
                            label = "Your rating ${state.userRating}",
                            icon = Icons.Filled.Star,
                            active = true,
                            onClick = {},
                        )
                    }
                }
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

        if (state.aiSlides.isNotEmpty() || state.aiLoading) {
            Spacer(Modifier.height(24.dp))
            AiInsightsRail(slides = state.aiSlides, loading = state.aiLoading, onExpand = { aiSlideExpanded = it })
        }

        if (state.ratingBadges.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            RatingsSection(badges = state.ratingBadges)
        }

        // Body order mirrors the phone app: Cast & Crew, Reviews, Production,
        // Episodes, Making of, Collection, More like this, details rows.
        if (state.cast.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            CastRail(cast = state.cast)
        }

        if (state.reviews.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            ReviewsRail(reviews = state.reviews)
        }

        if (state.production.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            ProductionRail(companies = state.production)
        }

        if (state.seasons.size > 1 || state.episodes.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            Text(
                text = "Episodes",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
            )
            SeasonChips(
                seasons = state.seasons.map { it.seasonNumber },
                selected = state.selectedSeason,
                fallbackSeasonCount = state.seasonCount,
                onSelect = onSelectSeason,
            )
            EpisodeList(
                episodes = state.episodes,
                loading = state.episodesLoading,
                watchStates = state.episodeWatchStates,
                onToggleWatched = onToggleEpisodeWatched,
            )
        }

        if (state.extraVideos.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            ExtrasRail(videos = state.extraVideos, title = state.title)
        }

        if (state.collectionItems.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            RailSection(
                title = state.collectionName ?: "Collection",
                items = state.collectionItems,
                onItemClick = { onOpenItem(it.id) },
            )
        }

        if (state.similar.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            RailSection(
                title = "More like this",
                items = state.similar,
                onItemClick = { onOpenItem(it.id) },
            )
        }

        if (state.detailRows.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            DetailRowsSection(rows = state.detailRows)
        }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SmartPlayButton(
    label: String,
    remainingMinutes: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = if (remainingMinutes != null && remainingMinutes > 0 && label != "Rewatch") {
        "$label · ~${remainingMinutes}m left"
    } else {
        label
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = 34.dp, vertical = 12.dp),
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 17.sp,
        )
    }
}

@Composable
private fun AiInsightsRail(
    slides: List<AiSlideUi>,
    loading: Boolean,
    onExpand: (AiSlideUi) -> Unit,
) {
    Column {
        Text(
            text = "AI Insights",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
        )
        if (loading && slides.isEmpty()) {
            Text(
                text = "Generating insights…",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = ScreenPadding),
            )
            return
        }
        if (slides.isEmpty()) return
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(horizontal = ScreenPadding),
        ) {
            items(slides, key = { it.id }) { slide ->
                var focused by remember { mutableStateOf(false) }
                Column(
                    modifier = Modifier
                        .width(280.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (focused) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        )
                        .onFocusChanged { focused = it.isFocused }
                        .clickable { onExpand(slide) }
                        .padding(16.dp),
                ) {
                    Text(
                        text = slide.label.uppercase(),
                        fontSize = 12.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    slide.tag?.let { tag ->
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = tag,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = slide.body,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun AiInsightOverlay(slide: AiSlideUi, onDismiss: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(onClick = onDismiss),
    ) {
        Column(
            modifier = Modifier
                .width(520.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(28.dp),
        ) {
            Text(
                text = slide.label.uppercase(),
                fontSize = 13.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = slide.body,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f),
            )
        }
    }
}

@Composable
private fun CastRail(cast: List<CastMemberUi>) {
    Column {
        Text(
            text = "Cast & Crew",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = ScreenPadding),
        ) {
            itemsIndexed(cast, key = { i, member -> "$i-${member.personId}" }) { _, member ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(96.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        if (member.profileUrl != null) {
                            AsyncImage(
                                model = member.profileUrl,
                                contentDescription = member.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Text(
                                text = member.name.trim().take(1).uppercase().ifBlank { "?" },
                                fontSize = 26.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = member.name,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    member.role?.let { role ->
                        Text(
                            text = role,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtrasRail(videos: List<ExtraVideoUi>, title: String) {
    val context = LocalContext.current
    Column {
        Text(
            text = "Making of $title",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = ScreenPadding),
        ) {
            items(videos, key = { it.id }) { video ->
                CrispyLandscapeCard(
                    item = com.crispy.tv.tv.ui.components.CrispyCardItem(
                        id = video.id,
                        title = video.name,
                        imageUrl = video.thumbnailUrl,
                        badge = "Trailer",
                    ),
                    onClick = {
                        video.url?.let { url ->
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        }
                    },
                    modifier = Modifier.width(220.dp),
                )
            }
        }
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
    watchStates: Map<String, EpisodeWatchStateUi>,
    onToggleWatched: (DetailEpisodeUi) -> Unit,
) {
    Spacer(Modifier.height(16.dp))
    when {
        loading -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = ScreenPadding)
                        .fillMaxWidth()
                        .height(64.dp)
                        .skeletonElement(),
                )
            }
        }
        episodes.isEmpty() -> {}
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            episodes.forEach { episode ->
                EpisodeRow(
                    episode = episode,
                    watchState = watchStates[episode.itemId],
                    onToggleWatched = { onToggleWatched(episode) },
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun EpisodeRow(
    episode: DetailEpisodeUi,
    watchState: EpisodeWatchStateUi?,
    onToggleWatched: () -> Unit,
) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (watchState?.isWatched == true) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Watched",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = episode.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
            val progress = watchState?.progressPercent?.takeIf { it > 0.0 && it < 100.0 && !watchState.isWatched }
            if (progress != null) {
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((progress / 100.0).toFloat())
                            .height(3.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(
                    if (watchState?.isWatched == true) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                )
                .clickable(onClick = onToggleWatched),
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Toggle watched",
                tint = if (watchState?.isWatched == true) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun HeaderActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    active -> MaterialTheme.colorScheme.secondaryContainer
                    focused -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            color = if (active) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun RatingsSection(badges: List<String>) {
    Column(modifier = Modifier.padding(horizontal = ScreenPadding)) {
        Text(
            text = "Ratings",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            badges.forEach { badge ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(badge, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ReviewsRail(reviews: List<ReviewUi>) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(horizontal = ScreenPadding),
    ) {
        items(reviews, key = { it.id }) { review ->
            var focused by remember { mutableStateOf(false) }
            Column(
                modifier = Modifier
                    .width(300.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (focused) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    )
                    .onFocusChanged { focused = it.isFocused }
                    .clickable { }
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = review.author,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    review.rating?.let { rating ->
                        Text(rating, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = review.content,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ProductionRail(companies: List<CompanyUi>) {
    Column {
        Text(
            text = "Production",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(horizontal = ScreenPadding),
        ) {
            items(companies, key = { it.id }) { company ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(120.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .width(110.dp)
                            .height(64.dp),
                    ) {
                        if (company.logoUrl != null) {
                            AsyncImage(
                                model = company.logoUrl,
                                contentDescription = company.name,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Text(
                                text = company.name.trim().take(1).uppercase(),
                                fontSize = 22.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = company.name,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRowsSection(rows: List<Pair<String, String>>) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = ScreenPadding),
    ) {
        rows.forEach { (label, value) ->
            Row {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(150.dp),
                )
                Text(
                    text = value,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

package com.crispy.tv.tv.ui.screens.detail

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.crispy.tv.ui.assets.R
import com.crispy.tv.tv.ui.components.CrispyLandscapeCard
import com.crispy.tv.tv.ui.components.RailSection
import com.crispy.tv.tv.ui.components.skeletonElement
import com.crispy.tv.tv.ui.theme.rememberDetailsSeedColor
import com.crispy.tv.tv.ui.theme.rememberDetailsTvColorScheme
import kotlin.math.roundToInt
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton

internal val ScreenPadding = 48.dp

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
    onAiInsightsClick: () -> Unit = {},
    onDismissAiInsights: () -> Unit = {},
    onSetRating: (Int?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val seed by rememberDetailsSeedColor(
        imageUrl = if (state.loading) null else state.artworkUrl,
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
                onAiInsightsClick = onAiInsightsClick,
                onDismissAiInsights = onDismissAiInsights,
                onSetRating = onSetRating,
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
    onAiInsightsClick: () -> Unit,
    onDismissAiInsights: () -> Unit,
    onSetRating: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    var expandedReview by remember { mutableStateOf<ReviewUi?>(null) }
    var showRatingDialog by remember { mutableStateOf(false) }
    var pendingRating by remember { mutableStateOf(0f) }
    var trailerPlaying by remember(state.trailers) { mutableStateOf(state.trailers.isNotEmpty()) }
    var trailerMuted by remember(state.trailers) { mutableStateOf(true) }
    var trailerFailed by remember(state.trailers) { mutableStateOf(false) }
    val context = LocalContext.current
    val density = LocalDensity.current
    val trailerStopScrollPx = remember(density) { with(density) { 380.dp.toPx() } }
    val trailerActive = trailerPlaying &&
        !trailerFailed &&
        state.trailers.isNotEmpty() &&
        scroll.value <= trailerStopScrollPx

    if (showRatingDialog) {
        androidx.compose.material3.MaterialTheme(
            colorScheme = androidx.compose.material3.darkColorScheme(
                primary = MaterialTheme.colorScheme.primary,
                onPrimary = MaterialTheme.colorScheme.onPrimary,
                secondaryContainer = MaterialTheme.colorScheme.secondaryContainer,
                onSecondaryContainer = MaterialTheme.colorScheme.onSecondaryContainer,
                background = MaterialTheme.colorScheme.surface,
                onBackground = MaterialTheme.colorScheme.onSurface,
                surface = MaterialTheme.colorScheme.surface,
                onSurface = MaterialTheme.colorScheme.onSurface,
                onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            AlertDialog(
                onDismissRequest = { showRatingDialog = false },
                title = { Text("Rate") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = if (pendingRating.roundToInt() == 0) "No rating" else "${pendingRating.roundToInt()}/10",
                        )
                        Slider(
                            value = pendingRating,
                            onValueChange = { pendingRating = it },
                            valueRange = 0f..10f,
                            steps = 9,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val ratingInt = pendingRating.roundToInt().coerceIn(0, 10)
                            onSetRating(if (ratingInt == 0) null else ratingInt)
                            showRatingDialog = false
                        },
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(
                            onClick = {
                                onSetRating(null)
                                showRatingDialog = false
                            },
                            enabled = pendingRating.roundToInt() != 0,
                        ) {
                            Text("Clear")
                        }
                        TextButton(onClick = { showRatingDialog = false }) {
                            Text("Cancel")
                        }
                    }
                },
            )
        }
    }

    if (state.aiStoryVisible && state.aiInsights != null) {
        AiInsightsStoryOverlay(
            result = state.aiInsights,
            title = state.title,
            artworkUrl = state.artworkUrl,
            onDismiss = onDismissAiInsights,
        )
    }

    if (expandedReview != null) {
        ReviewOverlay(review = expandedReview!!, onDismiss = { expandedReview = null })
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll),
        ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            AsyncImage(
                model = state.artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp),
            )
            if (trailerActive) {
                TvHeroTrailerLayer(
                    trailers = state.trailers,
                    shouldPlay = true,
                    isMuted = trailerMuted,
                    onAllSourcesFailed = { trailerFailed = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp),
                )
            }
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
                    HeaderActionButton(
                        label = if (state.isRated && state.userRating != null) {
                            "Rated ${state.userRating}"
                        } else {
                            "Rate"
                        },
                        icon = Icons.Filled.Star,
                        active = state.isRated,
                        onClick = {
                            pendingRating = (state.userRating ?: 0).toFloat()
                            showRatingDialog = true
                        },
                    )
                    HeaderActionButton(
                        label = "Share",
                        icon = Icons.Filled.Share,
                        active = false,
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "Check out ${state.title} on Crispy")
                            }
                            runCatching {
                                context.startActivity(Intent.createChooser(intent, "Share ${state.title}"))
                            }
                        },
                    )
                    if (state.trailers.isNotEmpty()) {
                        HeaderActionButton(
                            label = if (trailerPlaying) "Pause" else "Trailer",
                            icon = if (trailerPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            active = trailerPlaying,
                            onClick = { trailerPlaying = !trailerPlaying },
                        )
                        HeaderActionButton(
                            label = if (trailerMuted) "Muted" else "Sound on",
                            icon = if (trailerMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            active = !trailerMuted,
                            onClick = { trailerMuted = !trailerMuted },
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

        Spacer(Modifier.height(20.dp))
        AiInsightsButton(
            isLoading = state.aiLoading,
            onClick = onAiInsightsClick,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
        if (state.aiUnavailable) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "AI insights are unavailable right now.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = ScreenPadding),
            )
        }

        Spacer(Modifier.height(20.dp))
        RatingsPillsSection(
            itemRating = state.itemRating,
            titleRatings = state.titleRatings,
        )

        // Body order mirrors the phone app: Ratings, Cast & Crew, Reviews, Production,
        // Episodes, Making of, Collection, More like this, details rows.
        if (state.cast.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            CastRail(cast = state.cast)
        }

        if (state.reviews.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            ReviewsRail(
                reviews = state.reviews,
                onExpand = { expandedReview = it },
            )
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
                seasons = state.seasons.map { it.parent?.seasonNumber },
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
private fun ReviewsRail(
    reviews: List<ReviewUi>,
    onExpand: (ReviewUi) -> Unit,
) {
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
                    .clickable { onExpand(review) }
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
                        Spacer(Modifier.width(8.dp))
                    }
                    ReviewProviderBadge(provider = review.provider)
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
private fun ReviewProviderBadge(provider: String) {
    val logoRes = when (provider.trim().lowercase()) {
        "tmdb" -> R.raw.tmdb
        "trakt" -> R.raw.trakt
        else -> null
    } ?: return
    AsyncImage(
        model = logoRes,
        contentDescription = provider,
        modifier = Modifier.size(20.dp),
    )
}

@Composable
private fun ReviewOverlay(review: ReviewUi, onDismiss: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(onClick = onDismiss),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .width(560.dp)
                .heightIn(max = 620.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = review.author,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    review.rating?.let { rating ->
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = null,
                                tint = Color(0xFFFFD54F),
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = rating,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                ReviewProviderBadge(provider = review.provider)
            }

            Text(
                text = review.content.trim(),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f),
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            )

            Text(
                text = "Close",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDismiss)
                    .padding(vertical = 4.dp),
            )
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

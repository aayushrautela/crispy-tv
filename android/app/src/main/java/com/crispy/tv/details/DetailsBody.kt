package com.crispy.tv.details

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput

import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.home.HomeCatalogPosterCard
import com.crispy.tv.addons.model.MediaVideo
import com.crispy.tv.catalog.toCatalogItem
import com.crispy.tv.ui.components.skeletonElement
import com.crispy.tv.ui.theme.Dimensions

internal fun LazyListScope.detailsBodyContent(
    uiState: DetailsUiState,
    horizontalPadding: Dp,
    palette: DetailsPaletteColors,
    onRetry: () -> Unit,
    onSeasonSelected: (Int) -> Unit,
    onItemClick: (CatalogItem, String?) -> Unit,
    onPersonClick: (personId: String, profileUrl: String?) -> Unit = { _, _ -> },
    onEpisodeClick: (videoId: String) -> Unit = {},
    onToggleEpisodeWatched: (MediaVideo) -> Unit = {},
    onMakingOfVideoClick: (CrispyBackendClient.MetadataVideoView) -> Unit = {},
    onReviewClick: (CrispyBackendClient.MetadataReviewView) -> Unit = {},
    onEpisodeLongPress: (MediaVideo) -> Unit = {},
) {
    val details = uiState.details
    val titleDetail = uiState.titleDetail
    val titleRatings = uiState.titleRatings?.ratings
    val contentPadding = PaddingValues(horizontal = horizontalPadding)

    item(key = "body-top-spacer") {
        Spacer(modifier = Modifier.height(18.dp))
    }

    if (details == null) {
        if (uiState.isLoading) {
            item(key = "body-loading") {
                Row(
                    modifier = Modifier.padding(horizontal = horizontalPadding),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .skeletonElement(shape = CircleShape, color = DetailsSkeletonColors.Elevated),
                    )
                    Box(
                        modifier = Modifier
                            .width(170.dp)
                            .height(12.dp)
                            .skeletonElement(color = DetailsSkeletonColors.Base),
                    )
                }
            }
        } else {
            item(key = "body-error") {
                Column(modifier = Modifier.padding(horizontal = horizontalPadding)) {
                    Text(text = uiState.statusMessage.ifBlank { "Unable to load details." })
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = onRetry) { Text("Retry") }
                }
            }
        }
        return
    }

    item(key = "ratings") {
        RatingsSection(
            tmdbRating = details.rating,
            titleRatings = titleRatings,
            isLoading = uiState.ratingsIsLoading,
            horizontalPadding = horizontalPadding,
            contentPadding = contentPadding,
        )
    }

    val isShow = details.itemType.equals("show", ignoreCase = true) ||
        details.itemType.equals("anime", ignoreCase = true) ||
        details.itemType.equals("episode", ignoreCase = true)
    val leadingPeople = if (isShow) {
        titleDetail?.creators.orEmpty()
    } else {
        titleDetail?.directors.orEmpty()
    }
    val cast = (leadingPeople + titleDetail?.cast.orEmpty()).distinctBy { it.personId }
    if (cast.isNotEmpty()) {
        item(key = "cast-header") {
            Column(modifier = Modifier.padding(horizontal = horizontalPadding)) {
                Spacer(modifier = Modifier.height(18.dp))
                Text(text = "Cast & Crew", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
        item(key = "cast-row") {
            LazyRow(
                contentPadding = contentPadding,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(items = cast, key = { it.personId }, contentType = { "cast" }) { member ->
                    MetadataCastCard(
                        member = member,
                        onClick = { onPersonClick(member.personId, member.profileUrl) },
                    )
                }
            }
        }
    }

    val reviews = uiState.titleExtras?.reviews.orEmpty()
    if (reviews.isNotEmpty() || uiState.extrasIsLoading) {
        item(key = "reviews-header") {
            Column(modifier = Modifier.padding(horizontal = horizontalPadding)) {
                Spacer(modifier = Modifier.height(18.dp))
                Text(text = "Reviews", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
        item(key = "reviews-row") {
            LazyRow(
                contentPadding = contentPadding,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (reviews.isNotEmpty()) {
                    items(items = reviews, key = { it.id }, contentType = { "review" }) { review ->
                        MetadataReviewCard(
                            review = review,
                            modifier = Modifier.width(Dimensions.WideCardWidth),
                            onClick = { onReviewClick(review) },
                        )
                    }
                } else {
                    items(2, contentType = { "reviewSkeleton" }) {
                        DetailsReviewPlaceholder(modifier = Modifier.width(Dimensions.WideCardWidth))
                    }
                }
            }
        }
    }

    val production = (titleDetail?.production?.companies.orEmpty() + titleDetail?.production?.networks.orEmpty())
        .distinctBy { it.id }
    if (production.isNotEmpty()) {
        item(key = "production-header") {
            Column(modifier = Modifier.padding(horizontal = horizontalPadding)) {
                Spacer(modifier = Modifier.height(18.dp))
                Text(text = "Production", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
        item(key = "production-row") {
            LazyRow(
                contentPadding = contentPadding,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items = production, key = { it.id }, contentType = { "production" }) { entity ->
                    MetadataProductionCard(entity = entity)
                }
            }
        }
    }

    if (details.itemType != "movie" && (uiState.seasons.isNotEmpty() || uiState.episodesIsLoading)) {
        item(key = "episodes-header") {
            Column(modifier = Modifier.padding(horizontal = horizontalPadding)) {
                Spacer(modifier = Modifier.height(22.dp))
                Text(text = "Episodes", style = MaterialTheme.typography.titleMedium)
            }
        }

        if (uiState.episodesIsLoading && uiState.seasonEpisodes.isEmpty()) {
            item(key = "episodes-loading-row") {
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(
                    contentPadding = contentPadding,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(4, contentType = { "episodeSkeleton" }) {
                        EpisodeCardSkeleton(modifier = Modifier.width(Dimensions.WideCardWidth))
                    }
                }
            }
        } else if (uiState.seasons.isNotEmpty()) {
            val seasons = uiState.seasons
            val selectedSeason = uiState.selectedSeasonOrFirst
            if (seasons.isNotEmpty() && selectedSeason != null) {
                item(key = "seasons-chips-row") {
                    Spacer(modifier = Modifier.height(10.dp))
                    LazyRow(
                        contentPadding = contentPadding,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(seasons, key = { it }, contentType = { "seasonChip" }) { season ->
                            FilterChip(
                                selected = season == selectedSeason,
                                onClick = { onSeasonSelected(season) },
                                label = { Text("Season $season") },
                                leadingIcon = if (uiState.seasonWatchStates[season] == true) {
                                    {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = palette.accent,
                                        )
                                    }
                                } else {
                                    null
                                },
                                border = null,
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    labelColor = MaterialTheme.colorScheme.onSurface,
                                    selectedContainerColor = palette.accent,
                                    selectedLabelColor = palette.onAccent,
                                ),
                            )
                        }
                    }
                }

                val episodes = uiState.seasonEpisodes
                    .sortedWith(compareBy<MediaVideo> { it.episode ?: Int.MAX_VALUE }.thenBy { it.title })
                    .take(50)

                when {
                    uiState.episodesIsLoading && episodes.isEmpty() -> {
                        item(key = "episodes-skeleton-row") {
                            Spacer(modifier = Modifier.height(10.dp))
                            LazyRow(
                                contentPadding = contentPadding,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(4, contentType = { "episodeSkeleton" }) {
                                    EpisodeCardSkeleton(modifier = Modifier.width(Dimensions.WideCardWidth))
                                }
                            }
                        }
                    }

                    uiState.episodesStatusMessage.isNotBlank() && episodes.isEmpty() -> {
                        item(key = "episodes-status") {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = uiState.episodesStatusMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = horizontalPadding),
                            )
                        }
                    }

                    episodes.isNotEmpty() -> {
                        item(key = "episodes-row") {
                            val episodeListState = rememberLazyListState()
                            Spacer(modifier = Modifier.height(10.dp))
                            LaunchedEffect(uiState.highlightedEpisodeId, episodes.firstOrNull()?.id) {
                                val target = uiState.highlightedEpisodeId ?: return@LaunchedEffect
                                val index = episodes.indexOfFirst { it.id == target }
                                if (index >= 0) episodeListState.animateScrollToItem(index)
                            }
                            LazyRow(
                                state = episodeListState,
                                contentPadding = contentPadding,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(items = episodes, key = { it.id }, contentType = { "episode" }) { video ->
                                    EpisodeCard(
                                        video = video,
                                        isHighlighted = video.id == uiState.highlightedEpisodeId,
                                        watchState = uiState.episodeWatchStates[video.id] ?: EpisodeWatchState(),
                                        modifier = Modifier.width(Dimensions.WideCardWidth),
                                        onClick = { onEpisodeClick(video.id) },
                                        onLongPress = { onEpisodeLongPress(video) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    item(key = "making-of") {
        MakingOfVideosSection(
            videos = uiState.titleDetail?.videos.orEmpty(),
            baseTitle = details.title.substringBefore(':').trim().ifBlank { details.title },
            horizontalPadding = horizontalPadding,
            contentPadding = contentPadding,
            onVideoClick = onMakingOfVideoClick,
        )
    }

    val collectionName = uiState.titleExtras?.collectionName
    val collection = uiState.titleExtras?.collection.orEmpty()
    val collectionParts = collection.mapNotNull { it.toCatalogItem() }
    if (collectionParts.isNotEmpty()) {
        item(key = "collection-header") {
            Column(modifier = Modifier.padding(horizontal = horizontalPadding)) {
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = collectionName ?: "Franchise Collection",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
        item(key = "collection-row") {
            LazyRow(
                contentPadding = contentPadding,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items = collectionParts, key = { "${it.type}:${it.id}" }, contentType = { "poster" }) { item ->
                    val key = "details-collection-${item.itemId}"
                    HomeCatalogPosterCard(
                        item = item,
                        sharedElementKey = key,
                        onClick = { onItemClick(item, key) },
                    )
                }
            }
        }
    }

    val similar = (uiState.titleExtras?.similar.orEmpty()).mapNotNull { it.toCatalogItem() }
    if (similar.isNotEmpty()) {
        item(key = "similar-header") {
            Column(modifier = Modifier.padding(horizontal = horizontalPadding)) {
                Spacer(modifier = Modifier.height(18.dp))
                Text(text = "More like this", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
        item(key = "similar-row") {
            LazyRow(
                contentPadding = contentPadding,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items = similar, key = { "${it.type}:${it.id}" }, contentType = { "poster" }) { item ->
                    val key = "details-similar-${item.itemId}"
                    HomeCatalogPosterCard(
                        item = item,
                        sharedElementKey = key,
                        onClick = { onItemClick(item, key) },
                    )
                }
            }
        }
    }

    val detailRows = buildDetailsRows(details = details, titleDetail = titleDetail, titleExtras = uiState.titleExtras)
    if (detailRows.isNotEmpty()) {
        item(key = "detail-rows-header") {
            Column(modifier = Modifier.padding(horizontal = horizontalPadding)) {
                Spacer(modifier = Modifier.height(22.dp))
                val header = when (details.itemType) {
                    "show" -> "Show Details"
                    "anime" -> "Anime Details"
                    else -> "Movie Details"
                }
                Text(text = header, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
        item(key = "detail-rows") {
            Column(modifier = Modifier.padding(horizontal = horizontalPadding)) {
                detailRows.forEach { (label, value) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.widthIn(min = 100.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailsReviewPlaceholder(modifier: Modifier = Modifier) {
    ElevatedCard(
        modifier = modifier.height(168.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .height(18.dp)
                    .skeletonElement(color = DetailsSkeletonColors.Base),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .skeletonElement(color = DetailsSkeletonColors.Base),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.3f)
                    .height(14.dp)
                    .skeletonElement(color = DetailsSkeletonColors.Base),
            )
        }
    }
}

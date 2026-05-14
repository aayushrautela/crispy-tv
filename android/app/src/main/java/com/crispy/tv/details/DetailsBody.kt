package com.crispy.tv.details

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.catalog.CatalogItem
import androidx.compose.ui.unit.sp
import com.crispy.tv.home.HomeCatalogPosterCard
import com.crispy.tv.home.MediaVideo
import com.crispy.tv.metadata.toCatalogItem
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import com.crispy.tv.ui.components.skeletonElement
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import com.crispy.tv.home.LandscapeArtworkFrame
import com.crispy.tv.home.rememberLandscapeImageModel

private val MAKING_OF_VIDEO_TYPES = setOf("Behind the Scenes", "Bloopers", "Featurette", "Clip")

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun DetailsBody(
    uiState: DetailsUiState,
    onRetry: () -> Unit,
    onSeasonSelected: (Int) -> Unit,
    onItemClick: (CatalogItem) -> Unit,
    onPersonClick: (String) -> Unit = {},
    onEpisodeClick: (videoId: String) -> Unit = {},
    onToggleEpisodeWatched: (MediaVideo) -> Unit = {},
) {
    val details = uiState.details
    val titleDetail = uiState.titleDetail
    val titleRatings = uiState.titleRatings?.ratings
    val horizontalPadding = responsivePageHorizontalPadding()
    val contentPadding = PaddingValues(horizontal = horizontalPadding)

    var expandedReview by remember { mutableStateOf<CrispyBackendClient.MetadataReviewView?>(null) }
    var selectedEpisodeAction by remember { mutableStateOf<MediaVideo?>(null) }
    val reviewSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val episodeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (expandedReview != null) {
        val review = expandedReview!!
        ModalBottomSheet(
            onDismissRequest = { expandedReview = null },
            sheetState = reviewSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding)
                    .padding(top = 6.dp, bottom = 18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            review.author?.takeIf { it.isNotBlank() } ?: review.username?.takeIf { it.isNotBlank() } ?: "Review",
                            style = MaterialTheme.typography.titleMedium,
                        )

                        review.rating?.let {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Star,
                                    contentDescription = null,
                                    tint = Color(0xFFFFD54F)
                                )
                                Text("${it.toInt()}/10", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    ReviewProviderBadge(provider = review.provider)
                }

                Text(review.content.trim(), style = MaterialTheme.typography.bodyMedium)

                TextButton(
                    onClick = { expandedReview = null },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Close")
                }
            }
        }
    }

    if (selectedEpisodeAction != null) {
        val selectedEpisode = selectedEpisodeAction!!
        val watchState = uiState.episodeWatchStates[selectedEpisode.id] ?: EpisodeWatchState()
        val toggleLabel = if (watchState.isWatched) "Mark as unwatched" else "Mark as watched"

        ModalBottomSheet(
            onDismissRequest = { selectedEpisodeAction = null },
            sheetState = episodeSheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding)
                    .padding(top = 6.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(selectedEpisode.title, style = MaterialTheme.typography.titleMedium)
                formatLongDate(selectedEpisode.released)?.let { released ->
                    Text(
                        text = released,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Button(
                    onClick = {
                        selectedEpisodeAction = null
                        onEpisodeClick(selectedEpisode.id)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open episode")
                }

                TextButton(
                    onClick = {
                        selectedEpisodeAction = null
                        onToggleEpisodeWatched(selectedEpisode)
                    },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(toggleLabel)
                }
            }
        }
    }

    var selectedMakingOfVideo by remember { mutableStateOf<CrispyBackendClient.MetadataVideoView?>(null) }
    MakingOfVideoPlayerDialog(
        video = selectedMakingOfVideo,
        onDismiss = { selectedMakingOfVideo = null },
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Dimensions.PageBottomPadding)
    ) {
        Spacer(modifier = Modifier.height(18.dp))

        if (details == null) {
            if (uiState.isLoading) {
                Row(
                    modifier = Modifier.padding(horizontal = horizontalPadding),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(18.dp)
                                .skeletonElement(shape = CircleShape, color = DetailsSkeletonColors.Elevated)
                    )
                    Box(
                        modifier =
                            Modifier
                                .width(170.dp)
                                .height(12.dp)
                                .skeletonElement(color = DetailsSkeletonColors.Base)
                    )
                }
            } else {
                Text(
                    text = uiState.statusMessage.ifBlank { "Unable to load details." },
                    modifier = Modifier.padding(horizontal = horizontalPadding)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onRetry,
                    modifier = Modifier.padding(horizontal = horizontalPadding)
                ) {
                    Text("Retry")
                }
            }
            return
        }

        RatingsSection(
            tmdbRating = details.rating,
            titleRatings = titleRatings,
            isLoading = uiState.ratingsIsLoading,
            horizontalPadding = horizontalPadding,
            contentPadding = contentPadding,
        )

        if (details.directors.isNotEmpty() || details.creators.isNotEmpty()) {
            Spacer(modifier = Modifier.height(14.dp))
            if (details.directors.isNotEmpty()) {
                Text(
                    text = "Director: ${details.directors.joinToString()}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = horizontalPadding)
                )
            }
            if (details.creators.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Creator: ${details.creators.joinToString()}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = horizontalPadding)
                )
            }
        }

        val cast = titleDetail?.cast.orEmpty()
        if (cast.isNotEmpty()) {
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "Cast",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = horizontalPadding)
            )
            Spacer(modifier = Modifier.height(10.dp))
            LazyRow(
                contentPadding = contentPadding,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(items = cast, key = { it.id }) { member ->
                    MetadataCastCard(
                        member = member,
                        onClick = { onPersonClick(member.id) }
                    )
                }
            }
        }

        val reviews = uiState.titleExtras?.reviews.orEmpty()
        if (reviews.isNotEmpty() || uiState.extrasIsLoading) {
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "Reviews",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = horizontalPadding)
            )
            Spacer(modifier = Modifier.height(10.dp))
            LazyRow(
                contentPadding = contentPadding,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (reviews.isNotEmpty()) {
                    items(items = reviews, key = { it.id }) { review ->
                        MetadataReviewCard(
                            review = review,
                            modifier = Modifier.width(280.dp),
                            onClick = { expandedReview = review }
                        )
                    }
                } else {
                    items(2) {
                        DetailsReviewPlaceholder(modifier = Modifier.width(280.dp))
                    }
                }
            }
        }

        val production = (titleDetail?.production?.companies.orEmpty() + titleDetail?.production?.networks.orEmpty())
            .distinctBy { it.id }
        if (production.isNotEmpty()) {
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "Production",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = horizontalPadding)
            )
            Spacer(modifier = Modifier.height(10.dp))
            LazyRow(
                contentPadding = contentPadding,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(items = production, key = { it.id }) { entity ->
                    MetadataProductionCard(entity = entity)
                }
            }
        }

        if (details.mediaType != "movie" && uiState.seasons.isNotEmpty()) {
            Spacer(modifier = Modifier.height(22.dp))
            Text(
                text = "Episodes",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = horizontalPadding)
            )

            val seasons = uiState.seasons
            val selectedSeason = uiState.selectedSeasonOrFirst
            if (seasons.isNotEmpty() && selectedSeason != null) {
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(
                    contentPadding = contentPadding,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(seasons) { season ->
                        FilterChip(
                            selected = season == selectedSeason,
                            onClick = { onSeasonSelected(season) },
                            label = { Text("Season $season") },
                            shape = RoundedCornerShape(16.dp),
                            border = null,
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                labelColor = MaterialTheme.colorScheme.onSurface,
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                val episodes =
                    uiState.seasonEpisodes
                        .sortedWith(compareBy<MediaVideo> { it.episode ?: Int.MAX_VALUE }.thenBy { it.title })
                        .take(50)

                when {
                    uiState.episodesIsLoading && episodes.isEmpty() -> {
                        LazyRow(
                            contentPadding = contentPadding,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(4) {
                                EpisodeCardSkeleton(modifier = Modifier.width(280.dp))
                            }
                        }
                    }

                    uiState.episodesStatusMessage.isNotBlank() && episodes.isEmpty() -> {
                        Text(
                            text = uiState.episodesStatusMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = horizontalPadding)
                        )
                    }

                    episodes.isNotEmpty() -> {
                        LazyRow(
                            contentPadding = contentPadding,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(items = episodes, key = { it.id }) { video ->
                                EpisodeCard(
                                    video = video,
                                    watchState = uiState.episodeWatchStates[video.id] ?: EpisodeWatchState(),
                                    isHighlighted = video.id == uiState.highlightedEpisodeId,
                                    modifier = Modifier.width(280.dp),
                                    onClick = { onEpisodeClick(video.id) },
                                    onLongPress = { selectedEpisodeAction = video },
                                )
                            }
                        }
                    }
                }
            }
        }

        val makingOfVideos = uiState.titleDetail?.videos.orEmpty()
            .filter { video -> video.key.isNotBlank() && video.type in MAKING_OF_VIDEO_TYPES }
        if (makingOfVideos.isNotEmpty()) {
            Spacer(modifier = Modifier.height(18.dp))
            val baseTitle = details.title.substringBefore(':').trim().ifBlank { details.title }
            Text(
                text = "Making of $baseTitle",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = horizontalPadding),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(10.dp))
            LazyRow(
                contentPadding = contentPadding,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(items = makingOfVideos, key = { it.id }) { video ->
                    MakingOfCard(
                        video = video,
                        onClick = { selectedMakingOfVideo = video },
                    )
                }
            }
        }

        val collection = uiState.titleExtras?.collection ?: titleDetail?.collection
        collection?.let { col ->
            val collectionParts = col.parts.mapNotNull { it.toCatalogItem() }
            if (collectionParts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = col.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = horizontalPadding),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(
                    contentPadding = contentPadding,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(items = collectionParts, key = { "${it.type}:${it.id}" }) { item ->
                        HomeCatalogPosterCard(item = item, onClick = { onItemClick(item) })
                    }
                }
            }
        }

        val similar = (uiState.titleExtras?.similar ?: titleDetail?.similar.orEmpty()).mapNotNull { it.toCatalogItem() }
        if (similar.isNotEmpty()) {
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "More like this",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = horizontalPadding)
            )
            Spacer(modifier = Modifier.height(10.dp))
            LazyRow(
                contentPadding = contentPadding,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(items = similar, key = { "${it.type}:${it.id}" }) { item ->
                        HomeCatalogPosterCard(item = item, onClick = { onItemClick(item) })
                    }
                }
            }

        val detailRows = buildDetailsRows(details = details, titleDetail = titleDetail)
        if (detailRows.isNotEmpty()) {
            Spacer(modifier = Modifier.height(22.dp))

            val header = when (details.mediaType) {
                "series" -> "Show Details"
                "anime" -> "Anime Details"
                else -> "Movie Details"
            }
            Text(
                text = header,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = horizontalPadding)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Column(modifier = Modifier.padding(horizontal = horizontalPadding)) {
                detailRows.forEach { (label, value) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.widthIn(min = 100.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailsReviewPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
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
                .height(92.dp)
                .skeletonElement(color = DetailsSkeletonColors.Base),
        )
    }
}

@Composable
private fun MakingOfCard(
    video: CrispyBackendClient.MetadataVideoView,
    onClick: () -> Unit,
) {
    val imageModel = rememberLandscapeImageModel(video.thumbnailUrl, 280.dp)
    Column(
        modifier = Modifier
            .width(280.dp)
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LandscapeArtworkFrame(
            title = video.name.orEmpty(),
            imageModel = imageModel,
            onClick = null,
            modifier = Modifier.aspectRatio(16f / 9f),
        )
        Text(
            text = video.name.orEmpty(),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Suppress("DEPRECATION")
@Composable
private fun MakingOfVideoPlayerDialog(
    video: CrispyBackendClient.MetadataVideoView?,
    onDismiss: () -> Unit,
) {
    val videoKey = video?.key?.trim().orEmpty()
    if (video == null || videoKey.isBlank()) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = video.name.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                    factory = { context ->
                        WebView(context).apply {
                            webViewClient = WebViewClient()
                            settings.javaScriptEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            loadUrl("https://www.youtube.com/embed/$videoKey?autoplay=1&rel=0")
                        }
                    },
                )
                TextButton(onClick = onDismiss) {
                    Text("Close", color = Color.White)
                }
            }
        }
    }
}

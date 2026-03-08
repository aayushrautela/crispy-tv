package com.crispy.tv.details

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.crispy.tv.home.HomeCatalogPosterCard
import com.crispy.tv.home.MediaDetails
import com.crispy.tv.home.MediaVideo
import com.crispy.tv.metadata.tmdb.TmdbCastMember
import com.crispy.tv.metadata.tmdb.TmdbMovieDetails
import com.crispy.tv.metadata.tmdb.TmdbProductionEntity
import com.crispy.tv.metadata.tmdb.TmdbReview
import com.crispy.tv.metadata.tmdb.TmdbTvDetails
import com.crispy.tv.metadata.tmdb.TmdbTitleDetails
import com.crispy.tv.ui.components.skeletonElement
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import kotlin.math.roundToInt
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun DetailsBody(
    uiState: DetailsUiState,
    onRetry: () -> Unit,
    onSeasonSelected: (Int) -> Unit,
    onItemClick: (String, String) -> Unit,
    onPersonClick: (String) -> Unit = {},
    onEpisodeClick: (videoId: String) -> Unit = {},
    onToggleEpisodeWatched: (MediaVideo) -> Unit = {},
) {
    val details = uiState.details
    val tmdb = uiState.tmdbEnrichment
    val horizontalPadding = responsivePageHorizontalPadding()
    val contentPadding = PaddingValues(horizontal = horizontalPadding)

    var expandedReview by remember { mutableStateOf<TmdbReview?>(null) }
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
                Text(review.author, style = MaterialTheme.typography.titleMedium)

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
            omdbDetails = uiState.omdbDetails,
            isLoading = uiState.omdbIsLoading,
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

        if (uiState.tmdbIsLoading && tmdb == null) {
            Spacer(modifier = Modifier.height(18.dp))

            // Cast skeleton
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = horizontalPadding)
                        .width(60.dp)
                        .height(20.dp)
                        .skeletonElement(color = DetailsSkeletonColors.Base)
                 )
                 LazyRow(
                     contentPadding = contentPadding,
                     horizontalArrangement = Arrangement.spacedBy(16.dp),
                     userScrollEnabled = false
                 ) {
                     items(6) {
                         Column(
                             modifier = Modifier.width(100.dp),
                             horizontalAlignment = Alignment.CenterHorizontally,
                             verticalArrangement = Arrangement.spacedBy(8.dp)
                         ) {
                             Box(
                                 modifier =
                                     Modifier
                                         .size(80.dp)
                                         .skeletonElement(shape = CircleShape, color = DetailsSkeletonColors.Base)
                             )
                             Box(
                                 modifier =
                                     Modifier
                                         .width(60.dp)
                                         .height(12.dp)
                                         .skeletonElement(color = DetailsSkeletonColors.Base)
                             )
                         }
                     }
                 }
             }

            Spacer(modifier = Modifier.height(24.dp))

            // Reviews/Similar skeleton
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = horizontalPadding)
                        .width(80.dp)
                        .height(20.dp)
                        .skeletonElement(color = DetailsSkeletonColors.Base)
                 )
                 LazyRow(
                     contentPadding = contentPadding,
                     horizontalArrangement = Arrangement.spacedBy(12.dp),
                     userScrollEnabled = false
                 ) {
                     items(3) {
                         Box(
                             modifier = Modifier
                                 .width(280.dp)
                                 .height(120.dp)
                                 .skeletonElement(color = DetailsSkeletonColors.Base)
                         )
                     }
                 }
             }
         }

        val tmdbCast = tmdb?.cast.orEmpty()
        if (tmdbCast.isNotEmpty()) {
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
                items(items = tmdbCast, key = { it.id }) { member ->
                    TmdbCastCard(
                        member = member,
                        onClick = { onPersonClick(member.id.toString()) }
                    )
                }
            }
        }

        val reviews = tmdb?.reviews.orEmpty()
        if (reviews.isNotEmpty()) {
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
                items(items = reviews, key = { it.id }) { review ->
                    TmdbReviewCard(
                        review = review,
                        modifier = Modifier.width(280.dp),
                        onClick = { expandedReview = review }
                    )
                }
            }
        }

        val production = tmdb?.production.orEmpty().filter { !it.logoUrl.isNullOrBlank() }
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
                    TmdbProductionCard(entity = entity)
                }
            }
        }

        if (details.mediaType == "series" && uiState.seasons.isNotEmpty()) {
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
                            label = { Text("Season $season") }
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
                        Row(
                            modifier = Modifier.padding(horizontal = horizontalPadding),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(
                                text = "Loading episodes...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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

        tmdb?.collection?.takeIf { it.parts.isNotEmpty() }?.let { collection ->
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = collection.name,
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
                items(items = collection.parts, key = { "${it.type}:${it.id}" }) { item ->
                    HomeCatalogPosterCard(item = item, onClick = { onItemClick(item.id, item.type) })
                }
            }
        }

        val similar = tmdb?.similar.orEmpty()
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
                    HomeCatalogPosterCard(item = item, onClick = { onItemClick(item.id, item.type) })
                }
            }
        }

        val detailRows = buildDetailsRows(details = details, titleDetails = tmdb?.titleDetails)
        if (detailRows.isNotEmpty()) {
            Spacer(modifier = Modifier.height(22.dp))

            val header = if (details.mediaType == "series") "Show Details" else "Movie Details"
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
private fun RatingsSection(
    tmdbRating: String?,
    omdbDetails: com.crispy.tv.metadata.omdb.OmdbDetails?,
    isLoading: Boolean,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    contentPadding: PaddingValues,
) {
    val ratings = remember(tmdbRating, omdbDetails) { buildRatings(tmdbRating = tmdbRating, omdbDetails = omdbDetails) }
    if (ratings.isEmpty() && !isLoading) return

    Spacer(modifier = Modifier.height(18.dp))
    Text(
        text = "Ratings",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = horizontalPadding),
    )
    Spacer(modifier = Modifier.height(10.dp))

    LazyRow(
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        userScrollEnabled = ratings.isNotEmpty(),
    ) {
        if (ratings.isEmpty()) {
            items(2) {
                RatingPillPlaceholder()
            }
        } else {
            items(items = ratings, key = { it.key }) { rating ->
                RatingPill(rating = rating)
            }
        }
    }
}

@Composable
private fun RatingPill(rating: DetailsRatingPill, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.widthIn(min = 160.dp),
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                color = rating.badgeColor,
                contentColor = rating.badgeContentColor,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    val badgeText = rating.badgeText
                    if (badgeText != null) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    } else {
                        Icon(
                            imageVector = rating.badgeIcon ?: Icons.Filled.Star,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = rating.score,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = rating.source,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                )
            }
        }
    }
}

@Composable
private fun RatingPillPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(160.dp)
            .height(64.dp)
            .skeletonElement(shape = RoundedCornerShape(32.dp), color = DetailsSkeletonColors.Base),
    )
}

private data class DetailsRatingPill(
    val key: String,
    val source: String,
    val score: String,
    val badgeText: String?,
    val badgeColor: Color,
    val badgeContentColor: Color,
    val badgeIcon: ImageVector? = null,
)

private fun buildRatings(
    tmdbRating: String?,
    omdbDetails: com.crispy.tv.metadata.omdb.OmdbDetails?,
): List<DetailsRatingPill> {
    val ratings = mutableListOf<DetailsRatingPill>()
    tmdbRating?.trim()?.takeIf { it.isNotBlank() }?.let { rating ->
        ratings +=
            DetailsRatingPill(
                key = "tmdb",
                source = "TMDB",
                score = formatTmdbRating(rating),
                badgeText = "TMDB",
                badgeColor = Color(0xFF0D253F),
                badgeContentColor = Color.White,
            )
    }

    omdbDetails?.ratings.orEmpty().forEachIndexed { index, rating ->
        val source = rating.source.trim()
        val value = rating.value.trim()
        if (source.isBlank() || value.isBlank()) return@forEachIndexed

        ratings +=
            when {
                source.equals("Internet Movie Database", ignoreCase = true) -> {
                    DetailsRatingPill(
                        key = "omdb-imdb-$index",
                        source = "IMDb",
                        score = value,
                        badgeText = "IMDb",
                        badgeColor = Color(0xFFF5C518),
                        badgeContentColor = Color(0xFF121212),
                    )
                }

                source.equals("Rotten Tomatoes", ignoreCase = true) -> {
                    DetailsRatingPill(
                        key = "omdb-rt-$index",
                        source = "Rotten Tomatoes",
                        score = value,
                        badgeText = "RT",
                        badgeColor = Color(0xFFD32F2F),
                        badgeContentColor = Color.White,
                    )
                }

                source.equals("Metacritic", ignoreCase = true) -> {
                    DetailsRatingPill(
                        key = "omdb-mc-$index",
                        source = "Metacritic",
                        score = value,
                        badgeText = "MC",
                        badgeColor = Color(0xFF1B5E20),
                        badgeContentColor = Color.White,
                    )
                }

                else -> {
                    DetailsRatingPill(
                        key = "omdb-$index",
                        source = source,
                        score = value,
                        badgeText = null,
                        badgeColor = Color(0xFFE2E8F0),
                        badgeContentColor = Color(0xFF475569),
                    )
                }
            }
    }

    return ratings
}

private fun formatTmdbRating(value: String): String {
    val trimmed = value.trim()
    if (trimmed.contains('/')) return trimmed
    return "$trimmed/10"
}

@Composable
private fun TmdbCastCard(
    member: TmdbCastMember,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier =
            modifier
                .width(100.dp)
                .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val profileUrl = member.profileUrl?.trim().orEmpty()
        Surface(
            modifier = Modifier.size(80.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            if (profileUrl.isNotBlank()) {
                AsyncImage(
                    model = profileUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = initials(member.name),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Text(
            text = member.name,
            style =
                MaterialTheme.typography.labelLarge.copy(
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        member.character?.takeIf { it.isNotBlank() }?.let { character ->
            Text(
                text = character,
                style =
                    MaterialTheme.typography.labelMedium.copy(
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        textAlign = TextAlign.Center
                    ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SimpleCastItem(
    name: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.width(100.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            modifier = Modifier.size(80.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = initials(name),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            text = name,
            style =
                MaterialTheme.typography.labelLarge.copy(
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun initials(name: String): String {
    val parts =
        name
            .trim()
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() }

    if (parts.isEmpty()) return "?"
    if (parts.size == 1) return parts[0].take(1).uppercase()
    return (parts[0].take(1) + parts[1].take(1)).uppercase()
}

private fun buildDetailsRows(
    details: MediaDetails,
    titleDetails: TmdbTitleDetails?
): List<Pair<String, String>> {
    val rows = mutableListOf<Pair<String, String>>()

    when (titleDetails) {
        is TmdbTvDetails -> {
            titleDetails.status?.takeIf { it.isNotBlank() }?.let { rows += "STATUS" to it }

            formatLongDate(titleDetails.firstAirDate)?.let { rows += "FIRST AIR DATE" to it }
            formatLongDate(titleDetails.lastAirDate)?.let { rows += "LAST AIR DATE" to it }

            titleDetails.numberOfSeasons?.takeIf { it > 0 }?.let { rows += "SEASONS" to "$it" }
            titleDetails.numberOfEpisodes?.takeIf { it > 0 }?.let { rows += "EPISODES" to "$it" }

            val episodeRuntime = titleDetails.episodeRunTimeMinutes.filter { it > 0 }
            if (episodeRuntime.isNotEmpty()) {
                rows += "EPISODE RUNTIME" to "${episodeRuntime.joinToString(" - ")} min"
            }

            if (titleDetails.originCountries.isNotEmpty()) {
                rows += "ORIGIN COUNTRY" to titleDetails.originCountries.joinToString(", ")
            }

            titleDetails.originalLanguage?.takeIf { it.isNotBlank() }?.let {
                rows += "ORIGINAL LANGUAGE" to it.uppercase()
            }

            if (details.creators.isNotEmpty()) {
                rows += "CREATED BY" to details.creators.joinToString(", ")
            }
        }

        is TmdbMovieDetails -> {
            titleDetails.tagline?.takeIf { it.isNotBlank() }?.let {
                rows += "TAGLINE" to "\"$it\""
            }
            titleDetails.status?.takeIf { it.isNotBlank() }?.let { rows += "STATUS" to it }

            formatLongDate(titleDetails.releaseDate)?.let { rows += "RELEASE DATE" to it }

            val runtime = formatRuntimeMinutes(titleDetails.runtimeMinutes) ?: details.runtime?.takeIf { it.isNotBlank() }
            runtime?.let { rows += "RUNTIME" to it }

            formatCurrency(titleDetails.budget)?.let { rows += "BUDGET" to it }
            formatCurrency(titleDetails.revenue)?.let { rows += "REVENUE" to it }

            if (titleDetails.originCountries.isNotEmpty()) {
                rows += "ORIGIN COUNTRY" to titleDetails.originCountries.joinToString(", ")
            }

            titleDetails.originalLanguage?.takeIf { it.isNotBlank() }?.let {
                rows += "ORIGINAL LANGUAGE" to it.uppercase()
            }
        }

        else -> Unit
    }

    return rows
}

private fun formatCurrency(amount: Long?): String? {
    if (amount == null || amount <= 0) return null
    return try {
        NumberFormat
            .getCurrencyInstance(Locale.US)
            .apply { maximumFractionDigits = 0 }
            .format(amount)
    } catch (_: Throwable) {
        "$amount"
    }
}

private fun formatLongDate(date: String?): String? {
    val raw = date?.trim().orEmpty()
    if (raw.isBlank()) return null

    val iso = if (raw.length >= 10) raw.take(10) else raw
    return try {
        val parsed = LocalDate.parse(iso)
        parsed.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))
    } catch (_: Throwable) {
        date
    }
}

private fun formatRuntimeMinutes(minutes: Int?): String? {
    if (minutes == null || minutes <= 0) return null
    val h = minutes / 60
    val m = minutes % 60

    return when {
        h > 0 && m > 0 -> "$h hr $m min"
        h > 0 -> "$h hr"
        else -> "$m min"
    }
}

@Composable
private fun TmdbProductionCard(
    entity: TmdbProductionEntity,
    modifier: Modifier = Modifier
) {
    val logo = entity.logoUrl?.trim().orEmpty()
    if (logo.isBlank()) return

    Surface(
        modifier = modifier
            .width(160.dp)
            .height(56.dp),
        shape = MaterialTheme.shapes.large,
        color = Color(0xFFF7F3EA)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = logo,
                contentDescription = entity.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                alignment = Alignment.Center
            )
        }
    }
}

@Composable
private fun TmdbReviewCard(
    review: TmdbReview,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    ElevatedCard(
        modifier = modifier.height(168.dp),
        onClick = onClick,
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = review.author,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                review.rating?.let {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = null,
                            tint = Color(0xFFFFD54F),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "${it.toInt()}/10",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = review.content.replace("\n", " ").trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )

                review.createdAt?.takeIf { it.isNotBlank() }?.let { createdAt ->
                    Text(
                        text = createdAt.take(10),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun EpisodeCard(
    video: MediaVideo,
    watchState: EpisodeWatchState,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onLongPress: () -> Unit = {},
) {
    val progressFraction = (watchState.progressPercent / 100.0).coerceIn(0.0, 1.0).toFloat()

    ElevatedCard(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongPress),
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            ) {
                val thumbnail = video.thumbnailUrl?.trim().orEmpty()
                if (thumbnail.isNotBlank()) {
                    AsyncImage(
                        model = thumbnail,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        MaterialTheme.colorScheme.secondaryContainer
                                    )
                                )
                            )
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.55f)
                            )
                        )
                )

                val prefix = episodePrefix(video)
                if (prefix != null) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
                    ) {
                        Text(
                            text = prefix,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                if (watchState.isWatched) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.94f),
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = "Watched",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                } else if (watchState.progressPercent > 0.0) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.94f),
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        Text(
                            text = "${watchState.progressPercent.roundToInt()}%",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                if (!watchState.isWatched && watchState.progressPercent > 0.0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color.White.copy(alpha = 0.25f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progressFraction)
                                .background(MaterialTheme.colorScheme.tertiary),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                formatLongDate(video.released)?.let { released ->
                    Text(
                        text = released,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                video.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                    Text(
                        text = overview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

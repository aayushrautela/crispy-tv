package com.crispy.tv.details

import com.crispy.tv.backend.CrispyBackendClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.crispy.tv.R
import com.crispy.tv.ratings.formatRating
import com.crispy.tv.ratings.formatRatingOutOfTen
import com.crispy.tv.ratings.normalizeRatingText
import com.crispy.tv.ui.components.skeletonElement

@Composable
internal fun RatingsSection(
    tmdbRating: String?,
    content: CrispyBackendClient.MetadataContentView?,
    isLoading: Boolean,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
) {
    val ratings = remember(tmdbRating, content) { buildRatings(tmdbRating = tmdbRating, content = content) }
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
            val badgeLogoRes = rating.badgeLogoRes
            if (badgeLogoRes != null) {
                Box(
                    modifier = Modifier.size(36.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(id = badgeLogoRes),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
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
    val badgeLogoRes: Int? = null,
)

private fun buildRatings(
    tmdbRating: String?,
    content: CrispyBackendClient.MetadataContentView?,
): List<DetailsRatingPill> {
    val ratings = mutableListOf<DetailsRatingPill>()
    tmdbRating?.trim()?.takeIf { it.isNotBlank() }?.let { rating ->
        ratings +=
            DetailsRatingPill(
                key = "tmdb",
                source = "TMDB",
                score = formatTmdbRating(rating),
                badgeText = "TMDB",
                badgeColor = Color(0xFF01B4E4),
                badgeContentColor = Color.White,
            )
    }

    val contentRatings = content?.ratings
    contentRatings?.imdbRating?.let { imdbRating ->
        formatRatingOutOfTen(formatRating(imdbRating))?.let { score ->
            ratings +=
                DetailsRatingPill(
                    key = "content-imdb",
                    source = "IMDb",
                    score = score,
                    badgeText = "IMDb",
                    badgeColor = Color(0xFFF5C518),
                    badgeContentColor = Color(0xFF121212),
                )
        }
    }

    contentRatings?.rottenTomatoes?.let { rottenTomatoes ->
        normalizeRatingText(rottenTomatoes.toString())?.let { score ->
            ratings +=
                DetailsRatingPill(
                    key = "content-rt",
                    source = "Rotten Tomatoes",
                    score = "$score%",
                    badgeText = null,
                    badgeColor = Color.Transparent,
                    badgeContentColor = Color.Unspecified,
                    badgeLogoRes = R.drawable.ic_rotten_tomatoes,
                )
        }
    }

    contentRatings?.metacritic?.let { metacritic ->
        normalizeRatingText(metacritic.toString())?.let { score ->
            ratings +=
                DetailsRatingPill(
                    key = "content-metacritic",
                    source = "Metacritic",
                    score = "$score/100",
                    badgeText = null,
                    badgeColor = Color.Transparent,
                    badgeContentColor = Color.Unspecified,
                    badgeLogoRes = R.drawable.ic_metacritic,
                )
        }
    }

    contentRatings?.letterboxdRating?.let { letterboxdRating ->
        formatRating(letterboxdRating)?.let { score ->
            ratings +=
                DetailsRatingPill(
                    key = "content-letterboxd",
                    source = "Letterboxd",
                    score = "$score/5",
                    badgeText = "LB",
                    badgeColor = Color(0xFF202830),
                    badgeContentColor = Color.White,
                )
        }
    }

    contentRatings?.mdblistRating?.let { mdblistRating ->
        formatRatingOutOfTen(formatRating(mdblistRating))?.let { score ->
            ratings +=
                DetailsRatingPill(
                    key = "content-mdblist",
                    source = "MDBList",
                    score = score,
                    badgeText = "MDB",
                    badgeColor = Color(0xFF4338CA),
                    badgeContentColor = Color.White,
                )
        }
    }

    return ratings
}

private fun formatTmdbRating(value: String): String {
    return formatRatingOutOfTen(value) ?: value.trim()
}

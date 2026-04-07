package com.crispy.tv.details

import com.crispy.tv.backend.CrispyBackendClient
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.annotation.RawRes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.crispy.tv.R
import com.crispy.tv.ratings.formatRating
import com.crispy.tv.ratings.formatRatingOutOfTen
import com.crispy.tv.ratings.normalizeRatingText
import com.crispy.tv.ui.components.skeletonElement

@Composable
internal fun RatingsSection(
    tmdbRating: String?,
    content: CrispyBackendClient.MetadataContentView?,
    titleRatings: CrispyBackendClient.MetadataTitleRatings?,
    isLoading: Boolean,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
) {
    val ratings = remember(tmdbRating, content, titleRatings) {
        buildRatings(
            tmdbRating = tmdbRating,
            content = content,
            titleRatings = titleRatings,
        )
    }
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
                    AsyncImage(
                        model = badgeLogoRes,
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
    @param:RawRes val badgeLogoRes: Int? = null,
)

private fun buildRatings(
    tmdbRating: String?,
    content: CrispyBackendClient.MetadataContentView?,
    titleRatings: CrispyBackendClient.MetadataTitleRatings?,
): List<DetailsRatingPill> {
    val contentRatings = content?.ratings
    val resolvedTitleRatings = titleRatings

    return listOfNotNull(
        buildRatingPill(
            key = "tmdb",
            source = "TMDB",
            score = resolvedTitleRatings?.tmdb?.asOutOfTen() ?: tmdbRating?.trim()?.takeIf { it.isNotBlank() }?.let(::formatTmdbRating),
            badge = RatingBadgeSpec(
                logoRes = R.raw.tmdb,
                text = "TMDB",
                backgroundColor = Color(0xFF01B4E4),
                contentColor = Color.White,
            ),
        ),
        buildRatingPill(
            key = "imdb",
            source = "IMDb",
            score = resolvedTitleRatings?.imdb?.asOutOfTen() ?: contentRatings?.imdbRating?.asOutOfTen(),
            badge = RatingBadgeSpec(
                logoRes = R.raw.imdb,
                text = "IMDb",
                backgroundColor = Color(0xFFF5C518),
                contentColor = Color(0xFF121212),
            ),
        ),
        buildRatingPill(
            key = "trakt",
            source = "Trakt",
            score = resolvedTitleRatings?.trakt?.asOutOfTen(),
            badge = RatingBadgeSpec(
                logoRes = R.raw.trakt,
                text = "Trakt",
                backgroundColor = Color(0xFFED1C24),
                contentColor = Color.White,
            ),
        ),
        buildRatingPill(
            key = "rotten_tomatoes",
            source = "Rotten Tomatoes",
            score = resolvedTitleRatings?.rottenTomatoes?.asPercent() ?: contentRatings?.rottenTomatoes?.toDouble()?.asPercent(),
            badge = RatingBadgeSpec(
                logoRes = R.raw.rotten_tomatoes,
                text = "RT",
                backgroundColor = Color.Transparent,
                contentColor = Color.Unspecified,
            ),
        ),
        buildRatingPill(
            key = "audience",
            source = "Audience",
            score = resolvedTitleRatings?.audience?.asPercent(),
            badge = RatingBadgeSpec(
                text = "AUD",
                backgroundColor = Color(0xFF198754),
                contentColor = Color.White,
            ),
        ),
        buildRatingPill(
            key = "metacritic",
            source = "Metacritic",
            score = resolvedTitleRatings?.metacritic?.asOutOfHundred() ?: contentRatings?.metacritic?.toDouble()?.asOutOfHundred(),
            badge = RatingBadgeSpec(
                logoRes = R.raw.metacritic,
                text = "MC",
                backgroundColor = Color.Transparent,
                contentColor = Color.Unspecified,
            ),
        ),
        buildRatingPill(
            key = "letterboxd",
            source = "Letterboxd",
            score = resolvedTitleRatings?.letterboxd?.asOutOfFive() ?: contentRatings?.letterboxdRating?.asOutOfFive(),
            badge = RatingBadgeSpec(
                logoRes = R.raw.letterboxd,
                text = "LB",
                backgroundColor = Color(0xFF202830),
                contentColor = Color.White,
            ),
        ),
        buildRatingPill(
            key = "roger_ebert",
            source = "Roger Ebert",
            score = resolvedTitleRatings?.rogerEbert?.asOutOfFour(),
            badge = RatingBadgeSpec(
                text = "RE",
                backgroundColor = Color(0xFF111827),
                contentColor = Color.White,
            ),
        ),
        buildRatingPill(
            key = "my_anime_list",
            source = "MyAnimeList",
            score = resolvedTitleRatings?.myAnimeList?.asOutOfTen(),
            badge = RatingBadgeSpec(
                logoRes = R.raw.myanimelist,
                text = "MAL",
                backgroundColor = Color(0xFF2E51A2),
                contentColor = Color.White,
            ),
        ),
        buildRatingPill(
            key = "mdblist",
            source = "MDBList",
            score = contentRatings?.mdblistRating?.asOutOfTen(),
            badge = RatingBadgeSpec(
                text = "MDB",
                backgroundColor = Color(0xFF4338CA),
                contentColor = Color.White,
            ),
        ),
    )
}

private fun formatTmdbRating(value: String): String {
    return formatRatingOutOfTen(value) ?: value.trim()
}

@Stable
private data class RatingBadgeSpec(
    @param:RawRes val logoRes: Int? = null,
    val text: String,
    val backgroundColor: Color,
    val contentColor: Color,
)

private fun buildRatingPill(
    key: String,
    source: String,
    score: String?,
    badge: RatingBadgeSpec,
): DetailsRatingPill? {
    val resolvedScore = score?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return DetailsRatingPill(
        key = key,
        source = source,
        score = resolvedScore,
        badgeText = if (badge.logoRes == null) badge.text else null,
        badgeColor = badge.backgroundColor,
        badgeContentColor = badge.contentColor,
        badgeLogoRes = badge.logoRes,
    )
}

private fun Double?.asOutOfTen(): String? {
    return formatRatingOutOfTen(formatRating(this))
}

private fun Double?.asOutOfFive(): String? {
    return formatRating(this)?.let { "$it/5" }
}

private fun Double?.asOutOfFour(): String? {
    return formatRating(this)?.let { "$it/4" }
}

private fun Double?.asOutOfHundred(): String? {
    return normalizeRatingText(this?.toString())?.let { "$it/100" }
}

private fun Double?.asPercent(): String? {
    return normalizeRatingText(this?.toString())?.let { "$it%" }
}

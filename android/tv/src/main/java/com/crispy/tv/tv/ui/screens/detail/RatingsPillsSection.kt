package com.crispy.tv.tv.ui.screens.detail

import androidx.annotation.RawRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.crispy.tv.addons.util.formatRating
import com.crispy.tv.addons.util.formatRatingOutOfTen
import com.crispy.tv.addons.util.normalizeRatingText
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.tv.R

private data class TvRatingPill(
    val key: String,
    val source: String,
    val score: String,
    val badgeText: String?,
    val badgeColor: Color,
    val badgeContentColor: Color,
    @param:RawRes val badgeLogoRes: Int? = null,
)

@Composable
internal fun RatingsPillsSection(
    itemRating: Double?,
    titleRatings: CrispyBackendClient.MetadataTitleRatings?,
    modifier: Modifier = Modifier,
) {
    val pills = remember(itemRating, titleRatings) {
        buildRatingPills(itemRating = itemRating, titleRatings = titleRatings)
    }
    if (pills.isEmpty()) return

    Column(modifier = modifier.padding(horizontal = ScreenPadding)) {
        Text(
            text = "Ratings",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = ScreenPadding),
        ) {
            items(pills, key = { it.key }) { pill ->
                RatingPill(pill = pill)
            }
        }
    }
}

@Composable
private fun RatingPill(pill: TvRatingPill) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .widthIn(min = 160.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (pill.badgeLogoRes != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(36.dp),
            ) {
                AsyncImage(
                    model = pill.badgeLogoRes,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(pill.badgeColor),
            ) {
                Text(
                    text = pill.badgeText.orEmpty(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = pill.badgeContentColor,
                    maxLines = 1,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = pill.score,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = pill.source,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            )
        }
    }
}

private fun buildRatingPills(
    itemRating: Double?,
    titleRatings: CrispyBackendClient.MetadataTitleRatings?,
): List<TvRatingPill> = listOfNotNull(
    buildPill(
        key = "tmdb",
        source = "TMDB",
        score = titleRatings?.tmdb.asOutOfTen()
            ?: itemRating?.let(::formatRating)?.let(::formatTmdbRating),
        badge = RatingBadgeSpec(
            logoRes = R.raw.tmdb,
            text = "TMDB",
            backgroundColor = Color(0xFF01B4E4),
            contentColor = Color.White,
        ),
    ),
    buildPill(
        key = "imdb",
        source = "IMDb",
        score = titleRatings?.imdb.asOutOfTen(),
        badge = RatingBadgeSpec(
            logoRes = R.raw.imdb,
            text = "IMDb",
            backgroundColor = Color(0xFFF5C518),
            contentColor = Color(0xFF121212),
        ),
    ),
    buildPill(
        key = "trakt",
        source = "Trakt",
        score = titleRatings?.trakt.asOutOfTen(),
        badge = RatingBadgeSpec(
            logoRes = R.raw.trakt,
            text = "Trakt",
            backgroundColor = Color(0xFFED1C24),
            contentColor = Color.White,
        ),
    ),
    buildPill(
        key = "rotten_tomatoes",
        source = "Rotten Tomatoes",
        score = titleRatings?.rottenTomatoes.asPercent(),
        badge = RatingBadgeSpec(
            logoRes = R.raw.rotten_tomatoes,
            text = "RT",
            backgroundColor = Color.Transparent,
            contentColor = Color.Unspecified,
        ),
    ),
    buildPill(
        key = "audience",
        source = "Audience",
        score = titleRatings?.audience.asPercent(),
        badge = RatingBadgeSpec(
            text = "AUD",
            backgroundColor = Color(0xFF198754),
            contentColor = Color.White,
        ),
    ),
    buildPill(
        key = "metacritic",
        source = "Metacritic",
        score = titleRatings?.metacritic.asOutOfHundred(),
        badge = RatingBadgeSpec(
            logoRes = R.raw.metacritic,
            text = "MC",
            backgroundColor = Color.Transparent,
            contentColor = Color.Unspecified,
        ),
    ),
    buildPill(
        key = "letterboxd",
        source = "Letterboxd",
        score = titleRatings?.letterboxd.asOutOfFive(),
        badge = RatingBadgeSpec(
            logoRes = R.raw.letterboxd,
            text = "LB",
            backgroundColor = Color(0xFF202830),
            contentColor = Color.White,
        ),
    ),
    buildPill(
        key = "roger_ebert",
        source = "Roger Ebert",
        score = titleRatings?.rogerEbert.asOutOfFour(),
        badge = RatingBadgeSpec(
            text = "RE",
            backgroundColor = Color(0xFF111827),
            contentColor = Color.White,
        ),
    ),
    buildPill(
        key = "my_anime_list",
        source = "MyAnimeList",
        score = titleRatings?.myAnimeList.asOutOfTen(),
        badge = RatingBadgeSpec(
            logoRes = R.raw.myanimelist,
            text = "MAL",
            backgroundColor = Color(0xFF2E51A2),
            contentColor = Color.White,
        ),
    ),
)

private data class RatingBadgeSpec(
    @param:RawRes val logoRes: Int? = null,
    val text: String,
    val backgroundColor: Color,
    val contentColor: Color,
)

private fun buildPill(
    key: String,
    source: String,
    score: String?,
    badge: RatingBadgeSpec,
): TvRatingPill? {
    val resolvedScore = score?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return TvRatingPill(
        key = key,
        source = source,
        score = resolvedScore,
        badgeText = if (badge.logoRes == null) badge.text else null,
        badgeColor = badge.backgroundColor,
        badgeContentColor = badge.contentColor,
        badgeLogoRes = badge.logoRes,
    )
}

private fun formatTmdbRating(value: String): String =
    formatRatingOutOfTen(value) ?: value.trim()

private fun Double?.asOutOfTen(): String? =
    formatRating(this)?.let { formatRatingOutOfTen(it) }

private fun Double?.asOutOfFive(): String? =
    formatRating(this)?.let { "$it/5" }

private fun Double?.asOutOfFour(): String? =
    formatRating(this)?.let { "$it/4" }

private fun Double?.asOutOfHundred(): String? =
    normalizeRatingText(this?.toString())?.let { "$it/100" }

private fun Double?.asPercent(): String? =
    normalizeRatingText(this?.toString())?.let { "$it%" }

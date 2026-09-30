package com.crispy.tv.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.crispy.tv.addons.util.formatRating
import com.crispy.tv.addons.util.formatRatingOutOfTen
import com.crispy.tv.addons.util.normalizeRatingText
import com.crispy.tv.backend.MetadataTitleRatings
import com.crispy.tv.ui.components.CrispyIcon
import com.crispy.tv.ui.components.skeletonElement
import com.crispy.tv.ui.resources.Res
import com.crispy.tv.ui.resources.ic_star_filled
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * Which provider's logo a rating badge wears.
 *
 * `com.crispy.tv.ui.assets.R` is an Android resource class, so the badge used to carry a
 * raw resource id and that was the only thing keeping this file in `androidMain`. The
 * *identity* of a badge is not a resource -- it is a value -- so the identity lives here and
 * `DetailsRatingBadgeLogo` (androidMain) is the only thing that turns it into a drawable,
 * exactly as `ReviewProvider`/`reviewProviderOrNull` already do for the cast and reviews
 * rows.
 */
enum class RatingBadgeLogo {
    TMDB,
    IMDB,
    TRAKT,
    ROTTEN_TOMATOES,
    METACRITIC,
    LETTERBOXD,
    MYANIMELIST,
}

@Composable
internal fun RatingsSection(
    tmdbRating: String?,
    titleRatings: MetadataTitleRatings?,
    isLoading: Boolean,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    ratingBadgeLogo: @Composable (RatingBadgeLogo) -> Unit,
) {
    val ratings = remember(tmdbRating, titleRatings) {
        buildRatings(
            tmdbRating = tmdbRating,
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
                RatingPill(rating = rating, ratingBadgeLogo = ratingBadgeLogo)
            }
        }
    }
}

@Composable
private fun RatingPill(
    rating: DetailsRatingPill,
    ratingBadgeLogo: @Composable (RatingBadgeLogo) -> Unit,
    modifier: Modifier = Modifier,
) {
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
            val badgeLogo = rating.badgeLogo
            if (badgeLogo != null) {
                Box(
                    modifier = Modifier.size(36.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ratingBadgeLogo(badgeLogo)
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
                            CrispyIcon(
                                painter = painterResource(rating.badgeIcon ?: Res.drawable.ic_star_filled),
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

// `internal` rather than `private` because the badge decision is the part of this
// file the R-split made portable, and a decision nobody can call is a decision
// nobody can test. See DetailsRatingsSectionTest.
internal data class DetailsRatingPill(
    val key: String,
    val source: String,
    val score: String,
    val badgeText: String?,
    val badgeColor: Color,
    val badgeContentColor: Color,
    val badgeIcon: DrawableResource? = null,
    val badgeLogo: RatingBadgeLogo? = null,
)

internal fun buildRatings(
    tmdbRating: String?,
    titleRatings: MetadataTitleRatings?,
): List<DetailsRatingPill> {
    val resolvedTitleRatings = titleRatings

    return listOfNotNull(
        buildRatingPill(
            key = "tmdb",
            source = "TMDB",
            // The `takeIf` is deliberately redundant, and was kept deliberately. A mutation
            // that removed it fails nothing, and the reason is worth writing down rather
            // than rediscovering: `formatRatingOutOfTen` returns null for a blank string, so
            // `formatTmdbRating("")` is "" and `buildRatingPill`'s own `resolvedScore` check
            // drops the blank score a moment later. The guard restates a rule the formatter
            // already enforces and the pill builder re-enforces, which is why it is a cheap
            // local statement of "a blank fallback string is not a rating" rather than the
            // thing that makes it true. Removing it would also silently make this call site
            // depend on a behaviour of a function in another module.
            score = resolvedTitleRatings?.tmdb?.asOutOfTen() ?: tmdbRating?.trim()?.takeIf { it.isNotBlank() }?.let(::formatTmdbRating),
            badge = RatingBadgeSpec(
                logo = RatingBadgeLogo.TMDB,
                text = "TMDB",
                backgroundColor = Color(0xFF01B4E4),
                contentColor = Color.White,
            ),
        ),
        buildRatingPill(
            key = "imdb",
            source = "IMDb",
            score = resolvedTitleRatings?.imdb?.asOutOfTen(),
            badge = RatingBadgeSpec(
                logo = RatingBadgeLogo.IMDB,
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
                logo = RatingBadgeLogo.TRAKT,
                text = "Trakt",
                backgroundColor = Color(0xFFED1C24),
                contentColor = Color.White,
            ),
        ),
        buildRatingPill(
            key = "rotten_tomatoes",
            source = "Rotten Tomatoes",
            score = resolvedTitleRatings?.rottenTomatoes?.asPercent(),
            badge = RatingBadgeSpec(
                logo = RatingBadgeLogo.ROTTEN_TOMATOES,
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
            score = resolvedTitleRatings?.metacritic?.asOutOfHundred(),
            badge = RatingBadgeSpec(
                logo = RatingBadgeLogo.METACRITIC,
                text = "MC",
                backgroundColor = Color.Transparent,
                contentColor = Color.Unspecified,
            ),
        ),
        buildRatingPill(
            key = "letterboxd",
            source = "Letterboxd",
            score = resolvedTitleRatings?.letterboxd?.asOutOfFive(),
            badge = RatingBadgeSpec(
                logo = RatingBadgeLogo.LETTERBOXD,
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
                logo = RatingBadgeLogo.MYANIMELIST,
                text = "MAL",
                backgroundColor = Color(0xFF2E51A2),
                contentColor = Color.White,
            ),
        ),
    )
}

private fun formatTmdbRating(value: String): String {
    return formatRatingOutOfTen(value) ?: value.trim()
}

@Stable
internal data class RatingBadgeSpec(
    val logo: RatingBadgeLogo? = null,
    val text: String,
    val backgroundColor: Color,
    val contentColor: Color,
)

internal fun buildRatingPill(
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
        badgeText = if (badge.logo == null) badge.text else null,
        badgeColor = badge.backgroundColor,
        badgeContentColor = badge.contentColor,
        badgeLogo = badge.logo,
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

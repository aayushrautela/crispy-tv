package com.crispy.tv.details

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import coil3.compose.AsyncImage
import com.crispy.tv.ui.assets.R

/**
 * The androidMain half of the rating badge: the one place that turns a
 * [RatingBadgeLogo] into a drawable.
 *
 * `DetailsRatingsSection` is `commonMain` because the *decision* about which badge a rating
 * wears is pure -- it is a `when` over the rating source and formatting of the score, and
 * both are testable without a resource. What it cannot do is name `R.raw.*`, so it carries
 * the badge's identity and takes this composable as a slot. The layout of the badge -- the
 * 36dp box and the fallback coloured surface -- stays in the common half, because that is
 * design, not platform.
 *
 * Third instance in this repository of the same split: `ReviewProviderLogo` for the reviews
 * row, and the `R` split that moved `DetailsCastSection`.
 */
@Composable
internal fun DetailsRatingBadgeLogo(logo: RatingBadgeLogo) {
    AsyncImage(
        model =
            when (logo) {
                RatingBadgeLogo.TMDB -> R.raw.tmdb
                RatingBadgeLogo.IMDB -> R.raw.imdb
                RatingBadgeLogo.TRAKT -> R.raw.trakt
                RatingBadgeLogo.ROTTEN_TOMATOES -> R.raw.rotten_tomatoes
                RatingBadgeLogo.METACRITIC -> R.raw.metacritic
                RatingBadgeLogo.LETTERBOXD -> R.raw.letterboxd
                RatingBadgeLogo.MYANIMELIST -> R.raw.myanimelist
            },
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
    )
}

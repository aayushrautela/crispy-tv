package com.crispy.tv.details

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.crispy.tv.ui.assets.R

/**
 * The provider logo shown beside a review's date, and the reason the review
 * cards cannot be one file.
 *
 * This is the only part of the review card that needs Android: the two
 * identifiers come from `res/raw` in `:ui-assets`, and an `R` class is not
 * reachable from a KMP `commonMain` at all. Everything around it — the card,
 * the rating, the provider's human-readable label — is portable and lives in
 * `DetailsCastSection.kt`, which is in `commonMain`.
 *
 * The nine provider SVGs stay in `:ui-assets` on their own merits and are not
 * what is decided here: `:tv` and `:androidApp` reference the same `R.raw`
 * identifiers from plain Android source sets, and moving the files would be a
 * cross-cutting change to modules that never migrate. `:tv` carries its own
 * private copy of this badge for the same reason.
 */
@Composable
internal fun ReviewProviderBadge(provider: String) {
    val logoRes = reviewProviderLogoRes(provider) ?: return
    AsyncImage(
        model = logoRes,
        contentDescription = provider.providerLabel(),
        modifier = Modifier.size(width = 24.dp, height = 24.dp),
    )
}

/**
 * The resource id for a provider's logo, or null when there is no logo for it.
 *
 * Deliberately not a `when` on the raw string. The name matching lives in
 * `DetailsCastSection.kt` as [reviewProviderOrNull], so it can be tested in
 * `commonTest`; this only translates the result into an `R` identifier. An
 * earlier version matched the name *and* returned the identifier in one
 * function, which meant the test for it had to run on a JVM host test — where
 * loading `R$raw` fails outright.
 */
internal fun reviewProviderLogoRes(provider: String): Int? =
    when (provider.reviewProviderOrNull()) {
        ReviewProvider.TMDB -> R.raw.tmdb
        ReviewProvider.TRAKT -> R.raw.trakt
        null -> null
    }

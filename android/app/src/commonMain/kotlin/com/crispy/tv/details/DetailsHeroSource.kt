package com.crispy.tv.details

import com.crispy.tv.addons.model.MediaDetails
import com.crispy.tv.details.trailer.TrailerSource

/**
 * The two declarations `DetailsHero.kt` held above its composable, separated out
 * so the composable could move without them.
 *
 * Both are pure: one reads a field off an already-fetched `MediaDetails` and the
 * other is a two-field value. Neither touched the platform, and neither had any
 * business sitting in a 577-line file whose other tenant inflated a `PlayerView`.
 *
 * The package deliberately did not change, so `DetailsScreen` reaches both with no
 * import edit -- which is the same-package mechanism the `PlayerUiState`
 * extraction used, read from the other side.
 */
internal fun detailsHeroImageUrl(details: MediaDetails?): String? {
    return details?.artworkUrl
}

/**
 * One trailer candidate: the id the details screen keys on, and where to fetch it from.
 *
 * `public`, not `internal`, because it is a field of [HeroTrailerLayerArgs], and that type is
 * public only because `AppNavHostDependencies` -- which is public -- carries it in the
 * signature of its `homeHeroTrailerLayer` slot.
 */
data class HeroTrailerSource(
    val id: String,
    val source: TrailerSource,
)

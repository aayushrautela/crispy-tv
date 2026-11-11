package com.crispy.tv.details

import com.crispy.tv.addons.model.MediaDetails
import com.crispy.tv.details.trailer.TrailerSource
import com.crispy.tv.images.ImageQuality
import com.crispy.tv.images.ResponsiveImageSet

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
 * The URL the hero backdrop fetches: the widest tier the artwork set carries,
 * falling back to the flat URL.
 *
 * `urlFor(HIGH)` is the call that reaches `original` -- the uncapped tier the
 * app's ceiling keeps out of `urlForDisplaySize`, which exists so a card never
 * over-fetches -- and its fallback chain covers a set carrying fewer tiers, so
 * the answer degrades to `high`/`medium` rather than to nothing.
 *
 * Choosing through the set rather than the flat URL is what makes the details
 * response an upgrade: the flat URL *is* `artwork.medium`, so a hero that
 * resolves `medium` on both sides of the load is one request that never runs
 * again.
 */
internal fun detailsHeroArtworkUrl(
    artwork: ResponsiveImageSet?,
    fallbackUrl: String?,
): String? {
    return artwork
        ?.takeIf { !it.isEmpty }
        ?.urlFor(ImageQuality.HIGH)
        ?: fallbackUrl
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

package com.crispy.tv.details

import com.crispy.tv.addons.model.MediaDetails
import com.crispy.tv.details.trailer.TrailerSource
import com.crispy.tv.images.ImageQuality
import com.crispy.tv.images.ResponsiveImageSet
import com.crispy.tv.images.urlForDisplaySize

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
 * The URL the hero backdrop fetches: the widest tier the ladder can justify for
 * a surface this size, falling back to the flat URL.
 *
 * `cap` is `HIGH` -- the app-wide ceiling in `CrispyImage` clamps every surface
 * to `MEDIUM` so a card never over-fetches, and this is the one surface large
 * enough to need the rung above it. `urlForDisplaySize` therefore picks the
 * smallest listed rendition that covers [targetPx], and because `original` is
 * outside the ladder the answer tops out at `high` rather than at the uncapped
 * source: a hero box measured in height, not in kilobytes, is what sets the
 * rung, and a 4K file to fill a 1008px box is 1.8 MB for pixels nothing shows.
 * The fallback chains in `urlFor` still cover a set carrying fewer tiers.
 *
 * Choosing through the set rather than the flat URL is what makes the details
 * response an upgrade: the flat URL *is* `artwork.medium`, so a hero that
 * resolves `medium` on both sides of the load is one request that never runs
 * again.
 */
internal fun detailsHeroArtworkUrl(
    artwork: ResponsiveImageSet?,
    targetPx: Int,
    fallbackUrl: String?,
): String? {
    return artwork
        ?.takeIf { !it.isEmpty }
        ?.urlForDisplaySize(targetPx = targetPx, cap = ImageQuality.HIGH)
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

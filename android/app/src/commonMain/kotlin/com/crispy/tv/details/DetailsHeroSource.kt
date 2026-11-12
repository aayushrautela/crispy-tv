package com.crispy.tv.details

import com.crispy.tv.addons.model.MediaDetails
import com.crispy.tv.details.trailer.TrailerSource
import com.crispy.tv.images.ImageQuality
import com.crispy.tv.images.ResponsiveImageSet
import com.crispy.tv.images.urlForDisplaySize

/**
 * The declarations `DetailsHero.kt` held above its composable, separated out
 * so the composable could move without them.
 *
 * All of them are pure: one reads a field off an already-fetched `MediaDetails`,
 * one resolves a tier for a surface of a known size, and one turns a carried URL
 * into the set that decision can run against. None touched the platform, and
 * none had any business sitting in a 577-line file whose other tenant inflated a
 * `PlayerView`.
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
 * The set the hero starts with, before the details response has landed.
 *
 * The card that was tapped already knows the artwork -- `CatalogItem.artwork` is
 * the same `ResponsiveImageSet` the details response will carry -- but the route
 * cannot hand a set over, so what crosses is the one tier the hero would pick
 * anyway. And one tier is enough, for a reason about the hero's box rather than
 * about the ladder: `heroHeight` clamps between 300.dp and 520.dp while the hero
 * spans the screen's width, so `maxOf(widthPx, heightPx)` sits above
 * [com.crispy.tv.images.ImageRenditionWidths.HIGH_PX] on every device where a
 * hero is full-bleed. That is all of them. So the answer on both sides of the
 * load is the `HIGH` arm of [ResponsiveImageSet.urlFor], and a set holding only
 * that slot resolves to it at *every* target size: `LOW` and `MEDIUM` each
 * degrade up into `high` when their own tier is blank, and `HIGH` starts there.
 *
 * `original` is deliberately not carried. It is the uncapped source and the hero
 * does not want it: the measured 1008x897 box fetched 1.8 MB of `original` for
 * pixels nothing showed, which is the whole argument of [detailsHeroArtworkUrl].
 *
 * A blank or absent URL answers `null` -- which is also what a caller passes when
 * the tapped item carried no set at all -- and the hero then resolves its flat
 * URL, exactly as it did before this existed.
 */
internal fun detailsHeroInitialArtwork(highUrl: String?): ResponsiveImageSet? {
    return highUrl
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { ResponsiveImageSet(low = null, medium = null, high = it) }
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

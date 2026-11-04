package com.crispy.tv.images

import com.crispy.tv.settings.ImageQuality

/**
 * The pixel width each rendition of a [ResponsiveImageSet] is assumed to carry.
 *
 * The wire hands out three URLs per image (`small`/`medium`/`large`, mapped onto
 * `low`/`medium`/`high` by [responsiveImageSetFromDomainMap]) but no widths, and
 * nothing in this repo can measure the backend's files. These values are the
 * TMDB-style poster renditions (`w342`/`w500`/`w780`) the backend proxies -- the
 * same source `ios/ContractRunner`'s fixtures reference -- and the merged
 * `artwork` field is built server-side as `backdrop.small ?? poster.small`, so a
 * backdrop's renditions run wider (`w300`/`w780`/`w1280`) than a poster's. Treat
 * these as floors and confirm them against the live backend before letting a
 * surface drop below the tier that covers its display size.
 */
object ImageRenditionWidths {
    const val LOW_PX: Int = 342
    const val MEDIUM_PX: Int = 500
    const val HIGH_PX: Int = 780
}

/**
 * Picks the URL this set should be fetched at for a surface that will display it
 * at [targetPx] device pixels: the smallest rendition wide enough to cover the
 * target, never above [cap].
 *
 * This is the one place a rendition is chosen. Coil's `size()` only downsamples
 * at decode time -- the bytes downloaded are whatever URL the request carries --
 * so fetching a `medium` file for a 72.dp disc downloads the medium file in full
 * and throws most of it away. Choosing the URL here is what actually saves the
 * network bytes and the decode work.
 *
 * [cap] is the lower of the user's image-quality setting and the surface's own
 * ceiling (a disc never needs more than `low` no matter what the setting says),
 * compared on [ImageQuality]'s ordinal order. When no allowed rendition is wide
 * enough, the widest allowed one is returned -- upscaling a smaller bitmap beats
 * fetching a bigger file on a metered connection, and the fallback chains in
 * [ResponsiveImageSet.urlFor] still apply when a tier's URL is blank.
 */
fun ResponsiveImageSet.urlForDisplaySize(targetPx: Int, cap: ImageQuality): String? {
    val allowed = ImageQuality.entries.filter { it.ordinal <= cap.ordinal }
    val widths = mapOf(
        ImageQuality.LOW to ImageRenditionWidths.LOW_PX,
        ImageQuality.MEDIUM to ImageRenditionWidths.MEDIUM_PX,
        ImageQuality.HIGH to ImageRenditionWidths.HIGH_PX,
    )
    val chosen = allowed.firstOrNull { widths.getValue(it) >= targetPx } ?: allowed.last()
    return urlFor(chosen)
}

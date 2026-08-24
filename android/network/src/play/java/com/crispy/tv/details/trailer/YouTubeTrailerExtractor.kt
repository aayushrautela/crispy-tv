package com.crispy.tv.details.trailer

/**
 * Play-distribution stub: YouTube stream extraction is not bundled in this variant.
 * Returning null routes trailer playback to the direct-file sources (IMDb) and,
 * for YouTube links, to the embedded-player fallback in the UI layer.
 */
object YouTubeTrailerExtractor {
    fun resolve(
        videoId: String,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
    ): TrailerPlaybackSource? = null
}

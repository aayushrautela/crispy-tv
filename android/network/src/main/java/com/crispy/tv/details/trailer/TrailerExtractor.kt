package com.crispy.tv.details.trailer

/**
 * Resolves a YouTube id into directly playable stream URLs.
 *
 * ## Why this is an interface
 *
 * Extraction is done by NewPipeExtractor, which is a sideload-only engine: it
 * lives in `:android:youtube-extractor` and only the sideload variant depends on
 * that module. So the *type* has to be somewhere every consumer can see while
 * the *implementation* can be absent, which is what an interface in a
 * flavour-free module buys.
 *
 * The seam used to be a flavour axis on `:android:network` with two `object
 * YouTubeTrailerExtractor` definitions, one per source set. That could not
 * survive the Phase 1 split: `:app` became a Kotlin Multiplatform library, and
 * the KMP library plugin is single-variant, so it could not resolve which of
 * `:android:network`'s two variants to compile against. Gradle reported it as an
 * ambiguous variant rather than a missing one, which is the worst version of
 * that error to read.
 *
 * The flavour axis now lives only in `:androidApp`, and this interface is what
 * crosses it. See `DistributionComponents` in `:app` for the read path.
 */
interface TrailerExtractor {

    /**
     * Stream URLs for [videoId], sized for a [viewportWidthPx]-wide player, or
     * `null` when this build cannot extract them.
     *
     * `null` is a real answer, not a stub. A store build has no extractor, so
     * the caller falls back to the direct-file trailer source (IMDb) or, for a
     * YouTube link, the embedded-player path in the UI layer.
     *
     * Blocking network I/O; callers run it on `Dispatchers.IO`.
     */
    fun resolve(
        videoId: String,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
    ): TrailerPlaybackSource?
}

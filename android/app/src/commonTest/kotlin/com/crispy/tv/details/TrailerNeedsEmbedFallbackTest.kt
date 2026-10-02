package com.crispy.tv.details

import com.crispy.tv.details.trailer.TrailerSource
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `DetailsScreen` decides whether a YouTube trailer needs the embed fallback
 * surface. It could not be named before this landing because it read
 * `AppDistribution.current`, a seam whose Android implementation is the
 * application class itself, and because it lived inside a `@Composable` body
 * where no test can reach it.
 *
 * **The case that carries the weight is a DIRECT source with playback
 * unsupported.** A YouTube trailer with playback supported plays the native
 * player, so the embed surface would be a second player for the same video. A
 * *direct* trailer with playback unsupported is served by the native pipeline
 * regardless, so asking for the embed surface there would show a player that
 * cannot play it -- the answer must be `false`, and an implementation that
 * reduced this to "playback unsupported?" would answer `true`.
 */
class TrailerNeedsEmbedFallbackTest {

    @Test
    fun aYoutubeTrailerWithPlaybackUnsupportedNeedsTheEmbed() =
        assertEquals(
            true,
            trailerNeedsEmbedFallback(youtubePlaybackSupported = false, source = TrailerSource.YOUTUBE),
            "unsupported playback is exactly when the embed surface is the fallback",
        )

    @Test
    fun aYoutubeTrailerWithPlaybackSupportedIsPlayedNativelySoNoEmbedIsNeeded() =
        assertEquals(
            false,
            trailerNeedsEmbedFallback(youtubePlaybackSupported = true, source = TrailerSource.YOUTUBE),
            "the native player already handles it; an embed would be a second player for one video",
        )

    @Test
    fun aDirectSourceIsServedNativelySoUnsupportedPlaybackDoesNotChangeTheAnswer() =
        assertEquals(
            false,
            trailerNeedsEmbedFallback(youtubePlaybackSupported = false, source = TrailerSource.DIRECT),
            "a direct source never reaches the YouTube pipeline, embed or not",
        )

    @Test
    fun anAbsentSourceNeedsNoEmbedWhateverPlaybackIsSupported() =
        assertEquals(
            false,
            trailerNeedsEmbedFallback(youtubePlaybackSupported = false, source = null),
            "no source is not a YouTube source; the caller has nothing to embed",
        )

    @Test
    fun theOnlyTwoInputsThatChangeTheAnswerAreSupportAndBeingYoutube() {
        // A set comparison, not a hand-picked sample: a newly added `TrailerSource`
        // value must appear here, so a source nobody thought about fails the suite
        // rather than quietly answering false forever.
        val expected = mapOf(
            "youtube, supported" to false,
            "youtube, unsupported" to true,
            "direct, supported" to false,
            "direct, unsupported" to false,
            "absent, supported" to false,
            "absent, unsupported" to false,
        )
        val actual = buildMap {
            for (source in TrailerSource.entries.plus(null)) {
                for (supported in listOf(true, false)) {
                    val key = "${source?.name?.lowercase() ?: "absent"}, " +
                        if (supported) "supported" else "unsupported"
                    put(key, trailerNeedsEmbedFallback(supported, source))
                }
            }
        }
        assertEquals(expected.keys, actual.keys, "every source/support pair must be covered")
        for ((label, answer) in expected) {
            assertEquals(answer, actual[label], "trailerNeedsEmbedFallback disagreed for $label")
        }
    }
}
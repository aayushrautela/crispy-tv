package com.crispy.tv.addons.playback

import com.crispy.tv.addons.streams.AddonStream
import com.crispy.tv.addons.streams.StreamSubtitle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the rule that turns a provider stream into a playable source.
 *
 * ## Why this suite exists
 *
 * The rule was private to `:app`, so nothing could pin it, and the TV app was about to
 * write its own copy -- which is how a header-carrying stream and a header-less stream
 * end up playing differently on two surfaces. The rule now lives in `:addons`'s
 * `commonMain`, which means `commonTest` can reach it, and this suite is what says the
 * phone and the TV build the same source from the same stream.
 *
 * ## How to read a failure
 *
 * Every case states the invariant that broke, not the line that broke it. A failure
 * here means the two surfaces would disagree about what to play, not that a URL was
 * spelled differently.
 */
class AddonStreamPlaybackSourceTest {

    // ---------------------------------------------------------------- toDirectPlaybackSource

    @Test
    fun aDirectStreamBecomesASourceThatCarriesItsUrlItsHeadersAndItsSubtitles() {
        val stream = stream(
            url = "https://cdn.example.com/movie.mkv",
            requestHeaders = mapOf("Referer" to "https://example.com"),
            subtitles = listOf(StreamSubtitle(url = "https://cdn.example.com/en.vtt", lang = "eng", name = "English")),
        )

        val source = stream.toDirectPlaybackSource()

        assertEquals("https://cdn.example.com/movie.mkv", source?.url)
        assertEquals(mapOf("Referer" to "https://example.com"), source?.headers)
        assertEquals(1, source?.externalSubtitles?.size)
        assertEquals("https://cdn.example.com/en.vtt", source?.externalSubtitles?.first()?.url)
        assertEquals("eng", source?.externalSubtitles?.first()?.language)
        assertEquals("English", source?.externalSubtitles?.first()?.name)
    }

    @Test
    fun aMagnetLinkHasNoDirectSource() {
        val stream = stream(url = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567")

        assertNull(stream.toDirectPlaybackSource())
    }

    @Test
    fun aTorrentSchemeUrlHasNoDirectSource() {
        val stream = stream(url = "torrent://0123456789abcdef0123456789abcdef01234567/0")

        assertNull(stream.toDirectPlaybackSource())
    }

    @Test
    fun aStreamWithoutAUrlHasNoDirectSource() {
        assertNull(stream(url = null).toDirectPlaybackSource())
    }

    @Test
    fun aBlankUrlHasNoDirectSource() {
        assertNull(stream(url = "   ").toDirectPlaybackSource())
    }

    @Test
    fun aDirectSourceKeepsTheHeadersTheProviderAskedFor() {
        val stream = stream(
            url = "https://cdn.example.com/movie.mkv",
            requestHeaders = mapOf("Authorization" to "Bearer token", "User-Agent" to "CrispyTV"),
        )

        val source = stream.toDirectPlaybackSource()

        assertEquals("Bearer token", source?.headers?.get("Authorization"))
        assertEquals("CrispyTV", source?.headers?.get("User-Agent"))
    }

    @Test
    fun aDirectSourceWithNoSubtitlesCarriesAnEmptySubtitleList() {
        val source = stream(url = "https://cdn.example.com/movie.mkv").toDirectPlaybackSource()

        assertTrue(source?.externalSubtitles?.isEmpty() == true)
    }

    // ---------------------------------------------------------------- toPlaybackExternalSubtitles

    @Test
    fun aSubtitleWithABlankUrlIsDroppedRatherThanCarried() {
        val subtitles = listOf(
            StreamSubtitle(url = "   ", lang = "eng", name = "English"),
            StreamSubtitle(url = "https://cdn.example.com/en.vtt", lang = "eng", name = "English"),
        )

        val mapped = subtitles.toPlaybackExternalSubtitles()

        assertEquals(1, mapped.size)
        assertEquals("https://cdn.example.com/en.vtt", mapped.first().url)
    }

    @Test
    fun aSubtitleUrlIsTrimmedAndItsLanguageAndNameSurvive() {
        val subtitles = listOf(StreamSubtitle(url = "  https://cdn.example.com/en.vtt  ", lang = "eng", name = "English"))

        val mapped = subtitles.toPlaybackExternalSubtitles()

        assertEquals("https://cdn.example.com/en.vtt", mapped.first().url)
        assertEquals("eng", mapped.first().language)
        assertEquals("English", mapped.first().name)
    }

    @Test
    fun aSubtitleWithoutALanguageOrANameKeepsBothAsNull() {
        val subtitles = listOf(StreamSubtitle(url = "https://cdn.example.com/en.vtt", lang = null, name = null))

        val mapped = subtitles.toPlaybackExternalSubtitles()

        assertEquals(1, mapped.size)
        assertNull(mapped.first().language)
        assertNull(mapped.first().name)
    }

    // ---------------------------------------------------------------- fixture

    private fun stream(
        url: String?,
        requestHeaders: Map<String, String> = emptyMap(),
        subtitles: List<StreamSubtitle> = emptyList(),
    ): AddonStream =
        AddonStream(
            providerId = "provider",
            providerName = "Provider",
            url = url,
            stableKey = "stable-key",
            requestHeaders = requestHeaders,
            subtitles = subtitles,
        )
}

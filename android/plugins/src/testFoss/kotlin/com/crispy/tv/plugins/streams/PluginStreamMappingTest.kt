package com.crispy.tv.plugins.streams

import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.plugins.PluginStream
import com.crispy.tv.plugins.PluginSubtitle
import com.crispy.tv.plugins.repo.PluginScraperDescriptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PluginStreamMappingTest {

    private val scraper = PluginScraperDescriptor(
        repoUrl = "https://example.com/plugins.json",
        scraperId = "demo",
        displayName = "Demo Scraper",
        version = "1.0.0",
        supportedTypes = listOf("movie", "series"),
        code = "",
    )

    @Test
    fun `provider id uses plugin prefix`() {
        assertEquals("plugin:demo", providerId(scraper))
    }

    @Test
    fun `type support handles empty and canonical types`() {
        val allTypes = scraper.copy(supportedTypes = emptyList())
        assertTrue(allTypes.supports(MetadataLabMediaType.MOVIE))
        assertTrue(allTypes.supports(MetadataLabMediaType.ANIME))
        assertTrue(scraper.supports(MetadataLabMediaType.SERIES))
        val moviesOnly = scraper.copy(supportedTypes = listOf("movie"))
        assertTrue(moviesOnly.supports(MetadataLabMediaType.MOVIE))
        assertTrue(!moviesOnly.supports(MetadataLabMediaType.SERIES))
    }

    @Test
    fun `mapping produces provider-scoped AddonStream with merged headers`() {
        val stream = PluginStream(
            name = "Demo 1080p",
            url = "https://cdn.example.com/video.mp4",
            quality = "1080p",
            headers = mapOf("User-Agent" to "demo-agent"),
            referer = "https://example.com",
            subtitles = listOf(PluginSubtitle(url = "https://example.com/sub.vtt", lang = "en")),
            sizeBytes = 1_500_000_000L,
            audio = "AAC 2.0",
            filename = "demo.mp4",
        ).toAddonStream(scraper)

        assertEquals("plugin:demo", stream.providerId)
        assertEquals("Demo Scraper", stream.providerName)
        assertEquals("https://cdn.example.com/video.mp4", stream.url)
        assertEquals("demo-agent", stream.requestHeaders["User-Agent"])
        assertEquals("https://example.com", stream.requestHeaders["Referer"])
        assertEquals("1080p • 1.4 GB • AAC 2.0", stream.description)
        assertTrue(stream.stableKey.startsWith("plugin:demo-"))
        assertEquals("demo.mp4", stream.behaviorHints.filename)
        assertEquals(1, stream.subtitles.size)
        assertTrue(stream.hasPlayableSource)
    }

    @Test
    fun `mapping without referer leaves headers untouched`() {
        val stream = PluginStream(
            name = "plain",
            url = "https://cdn.example.com/v.mp4",
            quality = null,
            headers = emptyMap(),
            referer = null,
            subtitles = emptyList(),
            sizeBytes = null,
            audio = null,
            filename = null,
        ).toAddonStream(scraper)
        assertTrue(stream.requestHeaders.isEmpty())
        assertNull(stream.description)
    }

    @Test
    fun `lookup parsing extracts imdb id and season episode`() {
        val series = parseLookupComponents("tt1234567:2:5")
        assertEquals("tt1234567", series.imdbId)
        assertEquals(2, series.season)
        assertEquals(5, series.episode)
        assertNull(series.tmdbId)

        val imdbOnly = parseLookupComponents("tt7654321")
        assertEquals("tt7654321", imdbOnly.imdbId)
        assertNull(imdbOnly.season)

        val tmdb = parseLookupComponents("550")
        assertEquals(550, tmdb.tmdbId)
        assertNull(tmdb.imdbId)

        val empty = parseLookupComponents("")
        assertNull(empty.imdbId)
        assertNull(empty.tmdbId)
    }
}

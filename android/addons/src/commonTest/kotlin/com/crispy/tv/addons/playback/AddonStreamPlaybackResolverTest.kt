package com.crispy.tv.addons.playback

import com.crispy.tv.addons.streams.AddonStream
import com.crispy.tv.addons.streams.StreamSubtitle
import com.crispy.tv.player.TorrentResolver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which stream a provider's stream turns into, and who turns it.
 *
 * The rule has one caller per app and one shared implementation, so it is pinned
 * here rather than in either app: the phone and the TV have to agree about what a
 * magnet link means, and a build with no torrent engine has to say so rather than
 * fail later.
 */
class AddonStreamPlaybackResolverTest {

    @Test
    fun aDirectStreamIsPlayedWithoutAskingTheTorrentEngine() = runTest {
        val resolver = RecordingResolver("http://127.0.0.1:8080/stream")

        val source = directStream().resolvePlaybackSource(resolver, sessionId = "session-1")

        assertEquals("https://example.test/a.mkv", source?.url)
        assertEquals(null, resolver.lastMagnet, "the direct branch does not start the engine")
        assertEquals("Bearer token", source?.headers?.get("Authorization"), "the headers travel with the source")
        assertEquals(1, source?.externalSubtitles?.size, "the sidecar subtitles travel with the source")
    }

    @Test
    fun aMagnetStreamIsRefusedWhenThereIsNoTorrentEngine() = runTest {
        val source = magnetStream().resolvePlaybackSource(torrentResolver = null, sessionId = null)

        assertNull(source, "a build with no engine cannot play a magnet link")
    }

    @Test
    fun aMagnetStreamIsResolvedByTheTorrentEngine() = runTest {
        val resolver = RecordingResolver("http://127.0.0.1:8080/stream")

        val source = magnetStream().resolvePlaybackSource(resolver, sessionId = "session-1")

        assertEquals("http://127.0.0.1:8080/stream", source?.url)
        assertEquals(
            MAGNET,
            resolver.lastMagnet,
            "the magnet link is passed through as the provider wrote it",
        )
        assertEquals("session-1", resolver.lastSessionId, "the session id is passed through")
        assertEquals(0, source?.headers?.size, "the engine's URL carries no provider headers")
    }

    @Test
    fun aTorrentSchemeLinkBecomesAMagnetLinkForTheEngine() = runTest {
        val resolver = RecordingResolver("http://127.0.0.1:8080/stream")

        torrentSchemeStream().resolvePlaybackSource(resolver, sessionId = "session-1")

        assertEquals(
            "magnet:?xt=urn:btih:$INFO_HASH",
            resolver.lastMagnet,
            "a torrent:// link carries its hash, and the engine wants a magnet link",
        )
    }

    @Test
    fun aStreamWithNothingToPlayIsRefusedEvenWithTheEngine() = runTest {
        val resolver = RecordingResolver("http://127.0.0.1:8080/stream")

        val source = emptyStream().resolvePlaybackSource(resolver, sessionId = "session-1")

        assertNull(source, "there is nothing to play and nothing to resolve")
        assertEquals(null, resolver.lastMagnet, "the engine is never asked")
    }

    @Test
    fun aTorrentThatAlsoHasAPlainUrlIsPlayedDirectly() = runTest {
        val resolver = RecordingResolver("http://127.0.0.1:8080/stream")

        val source = torrentWithPlainUrlStream().resolvePlaybackSource(resolver, sessionId = "session-1")

        assertEquals("https://example.test/b.mkv", source?.url)
        assertEquals(null, resolver.lastMagnet, "the engine is not needed for a stream with a URL")
    }

    private fun directStream(): AddonStream = AddonStream(
        providerId = "atlas",
        providerName = "atlas",
        name = "direct",
        url = "https://example.test/a.mkv",
        requestHeaders = mapOf("Authorization" to "Bearer token"),
        stableKey = "direct",
        subtitles = listOf(
            StreamSubtitle(
                url = "https://example.test/a.srt",
                lang = "eng",
                name = "English",
            ),
        ),
    )

    private fun magnetStream(): AddonStream = AddonStream(
        providerId = "atlas",
        providerName = "atlas",
        name = "magnet",
        url = MAGNET,
        stableKey = "magnet",
    )

    private fun torrentSchemeStream(): AddonStream = AddonStream(
        providerId = "atlas",
        providerName = "atlas",
        name = "torrent scheme",
        url = "torrent://$INFO_HASH",
        stableKey = "torrent-scheme",
    )

    private fun torrentWithPlainUrlStream(): AddonStream = AddonStream(
        providerId = "atlas",
        providerName = "atlas",
        name = "torrent with a url",
        url = "https://example.test/b.mkv",
        infoHash = INFO_HASH,
        stableKey = "torrent-with-url",
    )

    private fun emptyStream(): AddonStream = AddonStream(
        providerId = "atlas",
        providerName = "atlas",
        name = "empty",
        stableKey = "empty",
    )

    private class RecordingResolver(private val answer: String) : TorrentResolver {
        var lastMagnet: String? = null
        var lastSessionId: String? = null

        override suspend fun resolveStreamUrl(magnetLink: String, sessionId: String): String {
            lastMagnet = magnetLink
            lastSessionId = sessionId
            return answer
        }

        override fun stopAndClear() = Unit

        override fun close() = Unit
    }

    private companion object {
        const val INFO_HASH = "0123456789abcdef0123456789abcdef01234567"
        const val MAGNET = "magnet:?xt=urn:btih:$INFO_HASH&dn=example"
    }
}

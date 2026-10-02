package com.crispy.tv.playerui

import com.crispy.tv.addons.streams.AddonStream
import com.crispy.tv.addons.streams.StreamSubtitle
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.player.TorrentResolver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `PlayerSessionSupport.kt` had **no coverage in any source set** before it moved to
 * `commonMain`, and it is the file that decides how an addon's chosen stream becomes
 * something an engine can play. Seven decisions live in 49 lines, and each of them picks
 * between two answers that are not otherwise distinguishable from the outside:
 *
 *  - a direct URL wins over a torrent, and only if it is non-blank;
 *  - a stream with neither a direct URL nor an info hash produces **no** source at all;
 *  - `MAGNET:` is recognised case-insensitively, and so is `torrent://`;
 *  - a `torrent://` link is **discarded as a direct URL and rebuilt** as a magnet from the
 *    hash it carries -- and it is the only link shape that takes that path, because
 *    `directPlaybackUrl` rejects exactly those two schemes and accepts everything else;
 *  - a missing session id becomes `""`, not a crash;
 *  - the torrent path carries **no headers** where the direct path carries the addon's;
 *  - a subtitle URL is trimmed, and a blank one drops the subtitle entirely.
 *
 * Every case below states the rule it pins in its own name, and every fixture is built so
 * that the two answers differ — a fixture whose paths agree proves nothing, because a
 * function that returned a constant would pass it.
 */
class AddonStreamToPlaybackSourceTest {

    /**
     * Records what the resolver was asked, and what it answered. `stopAndClear` and `close`
     * are the two port members this file never calls, so they fail the test if it ever does.
     */
    private class RecordingResolver(private val answer: String) : TorrentResolver {
        val asked = mutableListOf<Pair<String, String>>()
        override suspend fun resolveStreamUrl(magnetLink: String, sessionId: String): String {
            asked += magnetLink to sessionId
            return answer
        }

        override fun stopAndClear(): Unit = unused("stopAndClear")
        override fun close(): Unit = unused("close")

        private fun unused(name: String): Nothing = error("PlayerSessionSupport must not call $name")
    }

    private fun stream(
        url: String? = null,
        infoHash: String? = null,
        requestHeaders: Map<String, String> = emptyMap(),
        subtitles: List<StreamSubtitle> = emptyList(),
    ) = AddonStream(
        providerId = "provider",
        providerName = "A Provider",
        name = "a stream",
        url = url,
        infoHash = infoHash,
        requestHeaders = requestHeaders,
        subtitles = subtitles,
        stableKey = "key",
    )

    // --- 1. a direct URL wins, and carries the addon's headers ---------------------------

    @Test
    fun aDirectUrlIsUsedAsIsAndCarriesTheAddonsRequestHeaders() = runTest {
        val source = stream(
            url = "https://cdn.example/stream.m3u8",
            infoHash = "0123456789abcdef0123456789abcdef01234567",
            requestHeaders = mapOf("Referer" to "https://example"),
        ).toPlaybackSource(RecordingResolver("http://unused"), sessionId = null)

        assertEquals("https://cdn.example/stream.m3u8", source?.url)
        assertEquals(mapOf("Referer" to "https://example"), source?.headers)
    }

    /**
     * The header asymmetry is a decision, not an oversight: the direct path hands the
     * addon's headers to the engine, and the torrent path cannot, because the resolved URL
     * is a local HTTP endpoint the resolver owns. A fixture with `emptyMap()` on the direct
     * path would pass whether or not the headers were carried at all.
     */
    @Test
    fun aDirectUrlAndAnEmptyHeaderSetDoNotCollide() = runTest {
        val source = stream(url = "https://cdn.example/stream.m3u8")
            .toPlaybackSource(RecordingResolver("http://unused"), sessionId = null)

        assertEquals(emptyMap(), source?.headers)
    }

    // --- 2. a stream with neither answer produces no source -------------------------------

    @Test
    fun aStreamWithNeitherADirectUrlNorAnInfoHashProducesNoSource() = runTest {
        assertNull(stream().toPlaybackSource(RecordingResolver("http://unused"), sessionId = null))
    }

    /**
     * A blank URL is *not* a direct URL. `AddonStream.directPlaybackUrl` already rejects
     * blank, so this pins that the guard here is not what does it — and a body that tested
     * `isNullOrBlank()` against the raw `url` instead would answer the same thing, which is
     * why the next case is the one that separates them.
     */
    @Test
    fun aBlankUrlIsTreatedAsNoDirectUrl() = runTest {
        val resolver = RecordingResolver("http://resolved")
        val source = stream(url = "   ", infoHash = "0123456789abcdef0123456789abcdef01234567")
            .toPlaybackSource(resolver, sessionId = "s")

        assertEquals("http://resolved", source?.url)
    }

    // --- 3 & 4. the magnet link -------------------------------------------------------------

    @Test
    fun anInfoHashBecomesAMagnetLinkWhenTheStreamCarriesNoLink() = runTest {
        val resolver = RecordingResolver("http://127.0.0.1:8080/stream")
        val hash = "0123456789abcdef0123456789abcdef01234567"

        stream(infoHash = hash).toPlaybackSource(resolver, sessionId = "s")

        assertEquals(listOf("magnet:?xt=urn:btih:$hash" to "s"), resolver.asked)
    }

    /**
     * The case-insensitivity is the point, so the fixture is uppercase and the assertion is
     * that the link is passed through **unchanged** rather than rebuilt from the hash. If
     * the guard were case-sensitive the rebuilt link would be the magnet the fixture already
     * holds, and this would still pass — which is why the two pairs differ: the fixture's
     * own link names a different hash than `infoHash` does.
     */
    @Test
    fun anUppercaseMagnetLinkIsPassedThroughUnchanged() = runTest {
        val resolver = RecordingResolver("http://127.0.0.1:8080/stream")
        val fixtureLink = "MAGNET:?xt=urn:btih:fedcba9876543210fedcba9876543210fedcba98&dn=name"
        val infoHash = "0123456789abcdef0123456789abcdef01234567"

        stream(url = fixtureLink, infoHash = infoHash).toPlaybackSource(resolver, sessionId = "s")

        assertEquals(listOf(fixtureLink to "s"), resolver.asked)
    }

    @Test
    fun aLowercaseMagnetLinkIsAlsoPassedThroughUnchanged() = runTest {
        val resolver = RecordingResolver("http://127.0.0.1:8080/stream")
        val fixtureLink = "magnet:?xt=urn:btih:fedcba9876543210fedcba9876543210fedcba98"

        stream(url = fixtureLink).toPlaybackSource(resolver, sessionId = "s")

        assertEquals(listOf(fixtureLink to "s"), resolver.asked)
    }

    /**
     * A `torrent://` link is **discarded as a direct URL** and rebuilt as a magnet from the
     * hash it carries. `directPlaybackUrl` rejects exactly two schemes -- `magnet:` and
     * `torrent://` -- so this is the only link shape that reaches the rebuild, and the hash
     * here comes from the link itself rather than from a separate `infoHash` field.
     */
    @Test
    fun aTorrentSchemeLinkIsDiscardedAsADirectUrlAndRebuiltAsAMagnet() = runTest {
        val resolver = RecordingResolver("http://127.0.0.1:8080/stream")
        val hash = "0123456789abcdef0123456789abcdef01234567"

        stream(url = "torrent://$hash").toPlaybackSource(resolver, sessionId = "s")

        assertEquals(listOf("magnet:?xt=urn:btih:$hash" to "s"), resolver.asked)
    }

    /**
     * The counter-case, and the one that makes the rule above mean something: a plain https
     * link is a **direct** URL even on a stream that also carries an info hash, because
     * "not a magnet" is not the test -- the two rejected schemes are. An earlier version of
     * this suite asserted that such a link was discarded, and the code disagreed.
     */
    @Test
    fun aPlainHttpsLinkWinsOverAnInfoHashOnTheSameStream() = runTest {
        val resolver = RecordingResolver("http://unused")
        val hash = "0123456789abcdef0123456789abcdef01234567"

        val source = stream(url = "https://example/download", infoHash = hash)
            .toPlaybackSource(resolver, sessionId = "s")

        assertEquals("https://example/download", source?.url)
        assertTrue(resolver.asked.isEmpty())
    }

    @Test
    fun theExternalUrlIsUsedWhenThePrimaryUrlIsAbsent() = runTest {
        val resolver = RecordingResolver("http://127.0.0.1:8080/stream")
        val hash = "fedcba9876543210fedcba9876543210fedcba98"
        val withExternal = stream(infoHash = hash).copy(externalUrl = "MAGNET:?xt=urn:btih:other")

        withExternal.toPlaybackSource(resolver, sessionId = "s")

        assertEquals(listOf("MAGNET:?xt=urn:btih:other" to "s"), resolver.asked)
    }

    // --- 5. the session id fallback ---------------------------------------------------------

    /**
     * `sessionId ?: ""` rather than a nullable parameter. An absent session becomes the
     * empty string, so a resolver that keys sessions by id gets a key rather than a null —
     * and a resolver that receives `""` cannot tell it from a caller who passed one.
     */
    @Test
    fun anAbsentSessionIdBecomesTheEmptyStringRatherThanNull() = runTest {
        val resolver = RecordingResolver("http://127.0.0.1:8080/stream")

        stream(infoHash = "0123456789abcdef0123456789abcdef01234567")
            .toPlaybackSource(resolver, sessionId = null)

        assertEquals("", resolver.asked.single().second)
    }

    @Test
    fun aPresentSessionIdIsForwardedUnchanged() = runTest {
        val resolver = RecordingResolver("http://127.0.0.1:8080/stream")

        stream(infoHash = "0123456789abcdef0123456789abcdef01234567")
            .toPlaybackSource(resolver, sessionId = "session-42")

        assertEquals("session-42", resolver.asked.single().second)
    }

    /** The direct path must not consult the resolver at all — a torrent link would be wrong. */
    @Test
    fun aDirectUrlNeverConsultsTheResolver() = runTest {
        val resolver = RecordingResolver("http://unused")

        stream(url = "https://cdn.example/stream.m3u8").toPlaybackSource(resolver, sessionId = "s")

        assertTrue(resolver.asked.isEmpty())
    }

    // --- 6. the torrent path carries no headers --------------------------------------------

    @Test
    fun theTorrentPathCarriesNoHeadersEvenWhenTheAddonSuppliedThem() = runTest {
        val source = stream(
            url = "torrent://0123456789abcdef0123456789abcdef01234567",
            requestHeaders = mapOf("Referer" to "https://example"),
        ).toPlaybackSource(RecordingResolver("http://127.0.0.1:8080/stream"), sessionId = "s")

        assertEquals(emptyMap(), source?.headers)
    }

    // --- 7. the subtitle rules --------------------------------------------------------------

    @Test
    fun aSubtitleUrlIsTrimmedAndItsLanguageAndNameAreCarried() = runTest {
        val source = stream(url = "https://cdn.example/s.m3u8", subtitles = listOf(
            StreamSubtitle(url = "  https://cdn.example/en.vtt  ", lang = "eng", name = "English"),
        )).toPlaybackSource(RecordingResolver("http://unused"), sessionId = null)

        val subtitle = source?.externalSubtitles?.single()
        assertEquals("https://cdn.example/en.vtt", subtitle?.url)
        assertEquals("eng", subtitle?.language)
        assertEquals("English", subtitle?.name)
    }

    /**
     * A blank subtitle URL drops that subtitle and keeps the rest. The fixture carries one
     * blank and one usable, so a `map` instead of a `mapNotNull` would produce a
     * one-element list with an empty url rather than the same one-element list with the right
     * url — the two are only separated by the assertion on the url.
     */
    @Test
    fun aBlankSubtitleUrlDropsThatSubtitleAndKeepsTheOthers() = runTest {
        val source = stream(url = "https://cdn.example/s.m3u8", subtitles = listOf(
            StreamSubtitle(url = "   ", lang = "eng", name = "English"),
            StreamSubtitle(url = "https://cdn.example/fr.vtt", lang = "fre", name = "French"),
        )).toPlaybackSource(RecordingResolver("http://unused"), sessionId = null)

        assertEquals(listOf("https://cdn.example/fr.vtt"), source?.externalSubtitles?.map { it.url })
    }

    @Test
    fun subtitlesAreCarriedOnTheTorrentPathTooAndNotJustTheDirectOne() = runTest {
        val source = stream(
            infoHash = "0123456789abcdef0123456789abcdef01234567",
            subtitles = listOf(
                StreamSubtitle(url = "https://cdn.example/en.vtt", lang = "eng", name = "English"),
            ),
        ).toPlaybackSource(RecordingResolver("http://127.0.0.1:8080/stream"), sessionId = "s")

        assertEquals(listOf("https://cdn.example/en.vtt"), source?.externalSubtitles?.map { it.url })
    }
}

/**
 * `buildPlaybackRawId` is a one-line normalisation, and the three cases are the three
 * distinct answers it can give.
 */
class BuildPlaybackRawIdTest {

    @Test
    fun anItemIdIsTrimmedAndForwarded() {
        assertEquals(
            "tt1234567",
            buildPlaybackRawId(
                PlaybackIdentity(
                    itemId = "  tt1234567  ",
                    contentType = MetadataLabMediaType.MOVIE,
                    title = "A Film",
                ),
            ),
        )
    }

    @Test
    fun aBlankItemIdIsNoRawId() {
        assertNull(
            buildPlaybackRawId(
                PlaybackIdentity(
                    itemId = "   ",
                    contentType = MetadataLabMediaType.SERIES,
                    title = "A Show",
                ),
            ),
        )
    }

    @Test
    fun noIdentityIsNoRawId() {
        assertNull(buildPlaybackRawId(null))
    }
}

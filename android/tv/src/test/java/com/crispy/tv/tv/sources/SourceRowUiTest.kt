package com.crispy.tv.tv.sources

import com.crispy.tv.addons.streams.AddonStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the picker is allowed to do with a stream.
 *
 * The TV app ships the torrent engine, so a magnet link is playable here -- but it is
 * playable *through the engine*, which is a different thing from a plain URL, and the
 * row has to say which it is: the picker sorts the two differently, and the player
 * resolves them differently. These tests pin that difference, because the TV
 * refusing a row the phone plays is a bug in the TV.
 */
class SourceRowUiTest {

    @Test
    fun aPlainHttpRowIsPlayedDirectly() {
        val row = row(url = "https://example.test/stream.mkv")

        assertTrue("a plain HTTP stream is playable", row.isPlayable)
        assertFalse("a plain HTTP stream does not need the torrent engine", row.requiresTorrentEngine)
    }

    @Test
    fun aMagnetRowIsPlayedThroughTheTorrentEngine() {
        val row = row(url = "magnet:?xt=urn:btih:0000000000000000000000000000000000000000")

        assertTrue("a magnet link is a torrent", row.isTorrent)
        assertTrue("a magnet link needs the torrent engine", row.requiresTorrentEngine)
        assertTrue("a magnet link is still playable here", row.isPlayable)
    }

    @Test
    fun aTorrentSchemeRowIsPlayedThroughTheTorrentEngine() {
        val row = row(url = "torrent://0000000000000000000000000000000000000000")

        assertTrue("a torrent:// link is a torrent", row.isTorrent)
        assertTrue("a torrent:// link needs the torrent engine", row.requiresTorrentEngine)
        assertTrue("a torrent:// link is still playable here", row.isPlayable)
    }

    @Test
    fun aTorrentThatStillCarriesAPlainUrlIsPlayedDirectly() {
        // The phone plays this one directly: `toPlaybackSource` takes the direct
        // branch whenever there is a URL, and only falls back to the torrent engine
        // for a magnet link. The TV has to agree, or it treats a ready stream as one
        // that has to wait for an engine that is not needed.
        val row = row(
            url = "https://example.test/stream.mkv",
            infoHash = "0000000000000000000000000000000000000000",
        )

        assertTrue("a torrent with a plain URL is still a torrent", row.isTorrent)
        assertTrue("a torrent with a plain URL is playable", row.isPlayable)
        assertFalse(
            "a torrent with a plain URL does not need the torrent engine",
            row.requiresTorrentEngine,
        )
    }

    @Test
    fun aRowWithNoUrlAtAllIsNeitherPlayableNorATorrent() {
        val row = row(url = null)

        assertFalse("a row with no URL is not playable", row.isPlayable)
        assertFalse("a row with no URL is not a torrent", row.requiresTorrentEngine)
    }

    private fun row(url: String?, infoHash: String? = null): SourceRowUi =
        SourceRowUi(
            id = "row",
            providerId = "atlas",
            providerName = "atlas",
            title = "stream",
            stream = AddonStream(
                providerId = "atlas",
                providerName = "atlas",
                name = "stream",
                url = url,
                infoHash = infoHash,
                stableKey = "row",
            ),
        )
}

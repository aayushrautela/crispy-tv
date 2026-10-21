package com.crispy.tv.tv.sources

import com.crispy.tv.addons.streams.AddonStream
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The order the source rows are shown in.
 *
 * The rule exists because providers answer at different times: the list used to grow
 * in the order the answers arrived, so the row a viewer was reaching for moved while
 * they reached for it. These tests pin the order that replaced that.
 */
class TvSourceOrderingTest {

    @Test
    fun rowsAreOrderedByProviderNameAndNotByArrival() {
        val rows = listOf(
            row(provider = "Zenith", title = "4K"),
            row(provider = "atlas", title = "720p"),
            row(provider = "Beta", title = "1080p"),
        )

        assertEquals(
            "providers should be listed by name, case-insensitively, whatever order " +
                "their answers arrived in",
            listOf("atlas", "Beta", "Zenith"),
            TvSourceOrdering.order(rows).map { it.providerName },
        )
    }

    @Test
    fun torrentRowsSortAfterPlayableRowsInTheSameProvider() {
        val rows = listOf(
            row(provider = "atlas", title = "torrent", torrent = true),
            row(provider = "atlas", title = "direct"),
        )

        assertEquals(
            "a row the TV app cannot play should not sit above one it can",
            listOf("direct", "torrent"),
            TvSourceOrdering.order(rows).map { it.title },
        )
    }

    @Test
    fun aTorrentThatStillHasAPlainUrlIsNotSunkToTheBottom() {
        // It is playable here, so it does not take the place of the rows that are
        // not -- that is the difference between "is a torrent" and "would need the
        // torrent engine".
        val rows = listOf(
            row(provider = "atlas", title = "torrent with a url", order = 0, id = "a", infoHash = true),
            row(provider = "atlas", title = "direct", order = 1, id = "b"),
        )

        assertEquals(
            "a playable torrent keeps the provider's order instead of sinking below it",
            listOf("a", "b"),
            TvSourceOrdering.order(rows).map { it.id },
        )
    }

    @Test
    fun theProvidersOwnOrderIsKeptInsideAProvider() {
        val rows = listOf(
            row(provider = "atlas", title = "third", order = 2),
            row(provider = "atlas", title = "first", order = 0),
            row(provider = "atlas", title = "second", order = 1),
        )

        assertEquals(
            "the provider's own order for its streams is better than a sort on the title",
            listOf("first", "second", "third"),
            TvSourceOrdering.order(rows).map { it.title },
        )
    }

    @Test
    fun rowsThatAgreeOnEverythingElseAreOrderedByTitleThenId() {
        val rows = listOf(
            row(provider = "atlas", title = "same", order = 0, id = "b"),
            row(provider = "atlas", title = "same", order = 0, id = "a"),
            row(provider = "atlas", title = "another", order = 0, id = "c"),
        )

        assertEquals(
            "two rows that agree on provider, order and title still need a defined " +
                "order between them",
            listOf("c", "a", "b"),
            TvSourceOrdering.order(rows).map { it.id },
        )
    }

    @Test
    fun anEmptyListStaysEmpty() {
        assertEquals(
            "an empty list has no order to impose",
            emptyList<SourceRowUi>(),
            TvSourceOrdering.order(emptyList()),
        )
    }

    private fun row(
        provider: String,
        title: String,
        order: Int = 0,
        id: String = "$provider-$order-$title",
        torrent: Boolean = false,
        infoHash: Boolean = false,
    ): SourceRowUi {
        val url = when {
            torrent -> "magnet:?xt=urn:btih:0000000000000000000000000000000000000000"
            else -> "https://example.test/$id"
        }
        return SourceRowUi(
            id = id,
            providerId = provider.lowercase(),
            providerName = provider,
            title = title,
            streamOrder = order,
            stream = AddonStream(
                providerId = provider.lowercase(),
                providerName = provider,
                name = title,
                url = url,
                infoHash = if (infoHash) {
                    "0000000000000000000000000000000000000000"
                } else {
                    null
                },
                stableKey = id,
            ),
        )
    }
}

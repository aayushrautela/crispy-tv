package com.crispy.tv.tv.sources

/**
 * The order the source rows are shown in.
 *
 * ## Why this is a rule and not a side effect
 *
 * The rows used to arrive in the order their providers answered, because every
 * provider result was appended to the list as it came in. **That made the list move
 * under the viewer's finger**: a row that was third a moment ago became first when a
 * slower provider answered, and the row the viewer was about to press was no longer
 * where it was.
 *
 * The order is therefore decided by the rows themselves rather than by their arrival,
 * and it is decided in one place so it can be pinned: a provider's name, then its own
 * order for the stream inside that provider, then the title and the id so that two
 * rows that agree on everything else still have a defined order between them.
 *
 * Torrent rows sort last inside their provider, because a torrent that needs the
 * torrent engine is not playable on TV -- the TV app ships no torrent engine -- and
 * a row that cannot be played should not sit above one that can. A torrent that
 * still carries a plain HTTP URL is playable, so it is not treated as one of these.
 */
internal object TvSourceOrdering {

    fun order(rows: List<SourceRowUi>): List<SourceRowUi> =
        rows.sortedWith(
            compareBy<SourceRowUi> { it.providerName.lowercase() }
                .thenBy { it.providerName }
                .thenBy { it.requiresTorrentEngine }
                .thenBy { it.streamOrder }
                .thenBy { it.title.lowercase() }
                .thenBy { it.id },
        )
}

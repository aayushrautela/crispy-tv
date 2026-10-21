package com.crispy.tv.tv.player

import com.crispy.tv.addons.streams.AddonStream
import java.util.UUID

/**
 * The stream the viewer picked, handed to the player in process.
 *
 * ## Why this exists
 *
 * The pick used to travel as a route argument: `play/{itemId}?streamUrl={streamUrl}`,
 * with the URL URI-encoded into the navigation graph. That form cannot carry a
 * provider's request headers, and it cannot carry the stream's sidecar subtitles --
 * both of which the phone has always sent, because the phone never went through a
 * route to reach its player.
 *
 * So the pick is now a value held by [TvPlaybackSelectionStore] and named by an
 * opaque id, and the route carries the id. **The id is not the stream**: it is a key
 * into a process-scoped table, so the graph holds a short token and the stream --
 * with its headers, its subtitles and its length -- never has to survive a URL
 * round trip.
 *
 * ## Why the stream and not a playable source
 *
 * The pick carries the provider's [AddonStream], not a `PlaybackSource`, because a
 * magnet link is not a playable source until the torrent engine has turned it into
 * one -- and that takes time, and it can fail. **Resolving it when the player
 * starts is what lets the player say "preparing" and then say what went wrong**,
 * instead of the picker failing on a row the viewer never asked to leave.
 */
data class TvPlaybackSelection(
    /** The backend item the stream belongs to. */
    val itemId: String,
    /** The provider's stream, headers and sidecar subtitles included. */
    val stream: AddonStream,
    /** The lookup the source was resolved from, so the player can offer "change source". */
    val lookupMediaTypeName: String? = null,
    val lookupId: String? = null,
    /**
     * The episode the sources were resolved for, when there was one.
     *
     * These are carried **beside** the lookup id, not inside it, because a lookup id
     * cannot always name an episode -- the calendar rows resolve their episode from
     * the series' episode list, and even then the player needs the season and the
     * episode number to file the progress under the key the phone wrote.
     */
    val season: Int? = null,
    val episode: Int? = null,
)

/**
 * The process-scoped table of picks waiting to be played.
 *
 * A pick is **taken**, not read: the player consumes the entry when it starts, so a
 * stale pick cannot be replayed by a later visit to the player route.
 */
class TvPlaybackSelectionStore {
    private val selections = LinkedHashMap<String, TvPlaybackSelection>()

    @Synchronized
    fun put(selection: TvPlaybackSelection): String {
        val id = UUID.randomUUID().toString()
        selections[id] = selection
        return id
    }

    @Synchronized
    fun take(id: String?): TvPlaybackSelection? = if (id == null) null else selections.remove(id)
}

package com.crispy.tv.tv.home

import com.crispy.tv.addons.lookup.buildAddonEpisodeLookupId
import com.crispy.tv.addons.lookup.toMetadataLabMediaTypeOrNull
import com.crispy.tv.home.CalendarEpisodeItem
import com.crispy.tv.home.HomeWideRailItemUi
import com.crispy.tv.player.CanonicalContinueWatchingItem
import com.crispy.tv.player.MetadataLabMediaType

/**
 * What "play" means for one card on the TV home screen.
 *
 * ## Why this is a value and not an item id
 *
 * A home card knows more than an item id, and the difference decides **which episode
 * is played**: a Continue Watching row names the episode the viewer was watching, and
 * a calendar row names the episode that aired. If "play" carried only the title id,
 * the picker would ask the providers for the series' *next* episode -- the rule the
 * picker falls back to when it knows nothing else -- and the viewer's own episode
 * would be missed.
 *
 * It would also miss the progress. **The watch-history key carries the season and
 * the episode number** (`<series id>:<season>:<episode>`), so an episode played
 * without its season and number is filed under a key the phone never reads, and the
 * resume finds nothing.
 *
 * ## What the three fields are
 *
 * - `itemId` is the **title's** id: the series id for an episode, the movie id for a
 *   film. It is the id the progress is filed under.
 * - `lookupId` is the episode's address at the providers (`tt123:2:5`), when one is
 *   known.
 * - `season` and `episode` are carried alongside it because a lookup id cannot always
 *   be built: the calendar rows carry no imdb id, so the episode is resolved from
 *   the series' episode list instead, and the season and number still have to reach
 *   the player.
 */
data class TvHomePlayTarget(
    /** The title's id -- the series id for an episode, the movie id for a film. */
    val itemId: String,
    /** The media type's name, as `MetadataLabMediaType` names it. */
    val mediaTypeName: String? = null,
    /** The lookup id the providers are asked for, when one is known. */
    val lookupId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

/**
 * The play target of a rail card.
 *
 * A rail card carries one of two things depending on the rail it came from: a
 * watched title, or an episode that aired. Both name the title's id, and only the
 * watched one carries an imdb id.
 */
fun HomeWideRailItemUi.toPlayTarget(): TvHomePlayTarget {
    continueWatchingItem?.let { return it.toPlayTarget() }
    calendarEpisodeItem?.let { return it.toPlayTarget() }
    // A card with neither is a title with no episode: the picker works out what to
    // ask the providers for from the title id alone.
    return TvHomePlayTarget(itemId = detailsItemId ?: key)
}

/**
 * The play target of a watched title.
 *
 * The imdb id turns into a lookup id: with a season and an episode it is the
 * episode's address (`tt123:2:5`), and without them it is the title's (`tt123`).
 * A title with no imdb id has no lookup id at all, and the picker resolves one from
 * the title id.
 */
fun CanonicalContinueWatchingItem.toPlayTarget(): TvHomePlayTarget {
    val mediaType = itemType.toMetadataLabMediaTypeOrNull()
        ?: if (season != null && episode != null) MetadataLabMediaType.SERIES else MetadataLabMediaType.MOVIE
    return TvHomePlayTarget(
        itemId = titleItemId,
        mediaTypeName = mediaType.name,
        lookupId = buildAddonEpisodeLookupId(imdbId, season, episode),
        season = season,
        episode = episode,
    )
}

/**
 * The play target of an episode that aired.
 *
 * **There is no imdb id on this model**, so there is no lookup id either: the
 * episode is resolved from the series' episode list, and the season and the episode
 * number are what make the pick the right episode.
 */
fun CalendarEpisodeItem.toPlayTarget(): TvHomePlayTarget {
    val mediaType = type.toMetadataLabMediaTypeOrNull() ?: MetadataLabMediaType.SERIES
    return TvHomePlayTarget(
        itemId = titleItemId,
        mediaTypeName = mediaType.name,
        lookupId = null,
        season = season,
        episode = episode,
    )
}

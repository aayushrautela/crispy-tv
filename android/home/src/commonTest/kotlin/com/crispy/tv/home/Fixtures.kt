package com.crispy.tv.home

import com.crispy.tv.backend.CalendarItem
import com.crispy.tv.backend.ClientImages
import com.crispy.tv.backend.ClientMediaCard
import com.crispy.tv.backend.ClientParentImages
import com.crispy.tv.backend.ClientParentRef
import com.crispy.tv.backend.UpNextItem
import com.crispy.tv.images.ResponsiveImageSet

/**
 * Builders for the wire shapes `CalendarService` and `UpNextService` read.
 *
 * The two services are given a flat [CalendarItem] and [UpNextItem] each and do
 * all bucketing, grouping and key-building themselves, so a test says what the
 * server said and then asserts on what the service made of it. Naming the
 * fields a test cares about is why these are not raw constructors: a test that
 * had to spell out all thirteen [ClientMediaCard] fields to vary one of them
 * would eventually stop varying them.
 */

private val NO_IMAGES = ResponsiveImageSet(low = null, medium = null, high = null)

/** Artwork and still URLs, or nulls. The two services read `.medium` only. */
internal fun images(artwork: String? = null, still: String? = null) = ClientImages(
    artwork = ResponsiveImageSet(low = artwork, medium = artwork, high = artwork),
    logo = NO_IMAGES,
    still = ResponsiveImageSet(low = still, medium = still, high = still),
)

internal fun mediaCard(
    itemId: String,
    title: String = "Title $itemId",
    mediaType: String = "series",
    overview: String? = null,
    releaseDate: String? = null,
    images: ClientImages = images(),
    parent: ClientParentRef? = null,
): ClientMediaCard = ClientMediaCard(
    itemId = itemId,
    mediaType = mediaType,
    title = title,
    overview = overview,
    tagline = null,
    year = null,
    releaseDate = releaseDate,
    rating = null,
    maturityRating = null,
    genres = emptyList(),
    runtimeSeconds = null,
    images = images,
    trailerUrl = null,
    progress = null,
    parent = parent,
    providerIds = null,
)

/** The parent ref an episode card carries: which series, and which season/episode. */
internal fun parentRef(
    seriesItemId: String,
    seriesTitle: String = "Series $seriesItemId",
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
    images: ClientParentImages? = null,
) = ClientParentRef(
    seriesItemId = seriesItemId,
    seriesTitle = seriesTitle,
    seasonItemId = null,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    images = images,
)

/**
 * A calendar entry.
 *
 * [airDate] is the calendar's own field and wins over the card's [releaseDate];
 * [releaseDate] is the card's, which the service falls back to. Passing both is
 * the case that shows which one is read.
 */
internal fun calendarItem(
    card: ClientMediaCard,
    airDate: String? = null,
    bucket: String? = null,
) = CalendarItem(card = card, airDate = airDate, bucket = bucket)

internal fun upNextItem(
    show: ClientMediaCard?,
    nextEpisode: ClientMediaCard?,
    nextEpisodeAirDate: String? = null,
    lastInteractedAt: String? = null,
    reason: String? = null,
) = UpNextItem(
    show = show,
    nextEpisode = nextEpisode,
    nextEpisodeAirDate = nextEpisodeAirDate,
    lastInteractedAt = lastInteractedAt,
    reason = reason,
)

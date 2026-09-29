package com.crispy.tv.ui.navigation

import android.net.Uri
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.player.PlaybackIdentity

/**
 * Route builders, kept in `androidMain` because they percent-encode with
 * `android.net.Uri.encode`, whose allow-list is not RFC 3986 and not the same as
 * the form-urlencoded encoder `:android:core-domain` uses for catalog URLs.
 * The route *names* and *patterns* are plain strings and live in `commonMain`,
 * so every target can read them; only the encoding is Android's.
 *
 * These are extension functions on [AppRoutes] rather than members so that the
 * 19 existing call sites, all written `AppRoutes.homeDetailsRoute(...)`, do not
 * change. When a portable encoder that matches `Uri.encode` byte for byte
 * exists, these move into `commonMain` as members and this file goes away.
 */
fun AppRoutes.homeDetailsRoute(
    itemId: String,
    itemType: String,
    highlightEpisodeId: String? = null,
    autoOpenEpisode: Boolean = false,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
    absoluteEpisodeNumber: Int? = null,
    artworkUrl: String? = null,
    sharedElementKey: String? = null,
): String {
    return "$HomeDetailsRoute/${Uri.encode(itemType.trim())}/${Uri.encode(itemId.trim())}" +
        "?$HomeDetailsHighlightEpisodeIdArg=${Uri.encode(highlightEpisodeId.orEmpty())}" +
        "&$HomeDetailsAutoOpenEpisodeArg=${autoOpenEpisode}" +
        "&$HomeDetailsRuntimeSeasonNumberArg=${seasonNumber?.toString().orEmpty()}" +
        "&$HomeDetailsRuntimeEpisodeNumberArg=${episodeNumber?.toString().orEmpty()}" +
        "&$HomeDetailsRuntimeAbsoluteEpisodeArg=${absoluteEpisodeNumber?.toString().orEmpty()}" +
        "&$HomeDetailsArtworkUrlArg=${Uri.encode(artworkUrl.orEmpty())}" +
        "&$HomeDetailsSharedElementKeyArg=${Uri.encode(sharedElementKey.orEmpty())}"
}

fun AppRoutes.catalogListRoute(section: CatalogSectionRef): String {
    return "$CatalogListRoute/${Uri.encode(section.catalogId)}" +
        "?$CatalogTitleArg=${Uri.encode(section.displayTitle)}"
}

/**
 * Builds the player destination route. The launch payload mirrors the identity fields the
 * player session actually consumes; optional values travel as blank query parameters.
 */
fun AppRoutes.playerRoute(
    identity: PlaybackIdentity,
    resumePositionMs: Long = 0L,
    chosenStreamStableKey: String? = null,
    chosenProviderId: String? = null,
    chosenStreamHandoffKey: String? = null,
): String {
    return "$PlayerRoute?$PlayerMediaTypeArg=${Uri.encode(identity.contentType.name)}" +
        "&$PlayerItemIdArg=${Uri.encode(identity.itemId.orEmpty())}" +
        "&$PlayerSeriesItemIdArg=${Uri.encode(identity.seriesItemId.orEmpty())}" +
        "&$PlayerImdbIdArg=${Uri.encode(identity.imdbId.orEmpty())}" +
        "&$PlayerTmdbIdArg=${identity.tmdbId?.toString().orEmpty()}" +
        "&$PlayerSeasonArg=${identity.season?.toString().orEmpty()}" +
        "&$PlayerEpisodeArg=${identity.episode?.toString().orEmpty()}" +
        "&$PlayerYearArg=${identity.year?.toString().orEmpty()}" +
        "&$PlayerShowTitleArg=${Uri.encode(identity.showTitle.orEmpty())}" +
        "&$PlayerShowYearArg=${identity.showYear?.toString().orEmpty()}" +
        "&$PlayerParentMediaTypeArg=${Uri.encode(identity.parentMediaType.orEmpty())}" +
        "&$PlayerAbsoluteEpisodeNumberArg=${identity.absoluteEpisodeNumber?.toString().orEmpty()}" +
        "&$PlayerResumePositionMsArg=$resumePositionMs" +
        "&$PlayerChosenStreamKeyArg=${Uri.encode(chosenStreamStableKey.orEmpty())}" +
        "&$PlayerChosenProviderIdArg=${Uri.encode(chosenProviderId.orEmpty())}" +
        "&$PlayerChosenStreamHandoffKeyArg=${Uri.encode(chosenStreamHandoffKey.orEmpty())}"
}

fun AppRoutes.personDetailsRoute(personId: String, profileUrl: String? = null): String {
    return "$PersonDetailsRoute/${Uri.encode(personId.trim())}" +
        "?$PersonDetailsProfileUrlArg=${Uri.encode(profileUrl.orEmpty())}"
}

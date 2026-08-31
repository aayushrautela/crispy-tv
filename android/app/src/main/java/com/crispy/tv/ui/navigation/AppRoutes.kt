package com.crispy.tv.ui.navigation

import android.net.Uri
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.player.PlaybackIdentity

object AppRoutes {
    const val TopLevelScrollToTopRequestKey = "topLevelScrollToTopRequest"

    const val HomeRoute = "home"
    const val SearchRoute = "search"
    const val DiscoverRoute = "discover"
    const val LibraryRoute = "library"
    const val CalendarRoute = "calendar"
    const val SettingsRoute = "settings"

    const val HomeDetailsRoute = "home/details"
    const val HomeDetailsItemIdArg = "itemId"
    const val HomeDetailsItemTypeArg = "itemType"
    const val HomeDetailsHighlightEpisodeIdArg = "highlightEpisodeId"
    const val HomeDetailsAutoOpenEpisodeArg = "autoOpenEpisode"
    const val HomeDetailsRuntimeSeasonNumberArg = "runtimeSeasonNumber"
    const val HomeDetailsRuntimeEpisodeNumberArg = "runtimeEpisodeNumber"
    const val HomeDetailsRuntimeAbsoluteEpisodeArg = "runtimeAbsoluteEpisodeNumber"
    const val HomeDetailsArtworkUrlArg = "artworkUrl"
    const val HomeDetailsSharedElementKeyArg = "sharedElementKey"

    const val PersonDetailsRoute = "person/details"
    const val PersonDetailsPersonIdArg = "personId"
    const val PersonDetailsProfileUrlArg = "profileUrl"

    const val PlaybackSettingsRoute = "settings/playback"
    const val ImageSettingsRoute = "settings/image"
    const val AddonsSettingsRoute = "settings/addons"
    const val AccountsProfilesRoute = "settings/accounts"
    const val AuthRoute = "auth"
    const val ProfileManagementRoute = "settings/profiles"
    const val ProfileMenuRoute = "profile/menu"
    const val AccountSettingsRoute = "settings/account"

    const val CatalogListRoute = "catalog"
    const val CatalogIdArg = "catalogId"
    const val CatalogTitleArg = "title"

    const val PlayerRoute = "player"
    const val PlayerMediaTypeArg = "mediaType"
    const val PlayerItemIdArg = "itemId"
    const val PlayerSeriesItemIdArg = "seriesItemId"
    const val PlayerImdbIdArg = "imdbId"
    const val PlayerSeasonArg = "season"
    const val PlayerEpisodeArg = "episode"
    const val PlayerYearArg = "year"
    const val PlayerShowTitleArg = "showTitle"
    const val PlayerShowYearArg = "showYear"
    const val PlayerParentMediaTypeArg = "parentMediaType"
    const val PlayerAbsoluteEpisodeNumberArg = "absoluteEpisodeNumber"
    const val PlayerResumePositionMsArg = "resumePositionMs"
    const val PlayerChosenStreamKeyArg = "chosenStreamKey"
    const val PlayerChosenProviderIdArg = "chosenProviderId"
    const val PlayerChosenStreamHandoffKeyArg = "chosenStreamHandoffKey"

    // Details: itemId is the public title identity route segment.
    val HomeDetailsRoutePattern: String =
        "$HomeDetailsRoute/{$HomeDetailsItemTypeArg}/{$HomeDetailsItemIdArg}" +
            "?$HomeDetailsHighlightEpisodeIdArg={$HomeDetailsHighlightEpisodeIdArg}" +
            "&$HomeDetailsAutoOpenEpisodeArg={$HomeDetailsAutoOpenEpisodeArg}" +
            "&$HomeDetailsRuntimeSeasonNumberArg={$HomeDetailsRuntimeSeasonNumberArg}" +
            "&$HomeDetailsRuntimeEpisodeNumberArg={$HomeDetailsRuntimeEpisodeNumberArg}" +
            "&$HomeDetailsRuntimeAbsoluteEpisodeArg={$HomeDetailsRuntimeAbsoluteEpisodeArg}" +
            "&$HomeDetailsArtworkUrlArg={$HomeDetailsArtworkUrlArg}" +
            "&$HomeDetailsSharedElementKeyArg={$HomeDetailsSharedElementKeyArg}"
    val PersonDetailsRoutePattern: String =
        "$PersonDetailsRoute/{$PersonDetailsPersonIdArg}" +
            "?$PersonDetailsProfileUrlArg={$PersonDetailsProfileUrlArg}"
    val CatalogListRoutePattern: String =
        "$CatalogListRoute/{$CatalogIdArg}" +
            "?$CatalogTitleArg={$CatalogTitleArg}"

    fun homeDetailsRoute(
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

    fun catalogListRoute(section: CatalogSectionRef): String {
        return "$CatalogListRoute/${Uri.encode(section.catalogId)}" +
            "?$CatalogTitleArg=${Uri.encode(section.displayTitle)}"
    }

    val PlayerRoutePattern: String =
        "$PlayerRoute?$PlayerMediaTypeArg={$PlayerMediaTypeArg}" +
            "&$PlayerItemIdArg={$PlayerItemIdArg}" +
            "&$PlayerSeriesItemIdArg={$PlayerSeriesItemIdArg}" +
            "&$PlayerImdbIdArg={$PlayerImdbIdArg}" +
            "&$PlayerSeasonArg={$PlayerSeasonArg}" +
            "&$PlayerEpisodeArg={$PlayerEpisodeArg}" +
            "&$PlayerYearArg={$PlayerYearArg}" +
            "&$PlayerShowTitleArg={$PlayerShowTitleArg}" +
            "&$PlayerShowYearArg={$PlayerShowYearArg}" +
            "&$PlayerParentMediaTypeArg={$PlayerParentMediaTypeArg}" +
            "&$PlayerAbsoluteEpisodeNumberArg={$PlayerAbsoluteEpisodeNumberArg}" +
            "&$PlayerResumePositionMsArg={$PlayerResumePositionMsArg}" +
            "&$PlayerChosenStreamKeyArg={$PlayerChosenStreamKeyArg}" +
            "&$PlayerChosenProviderIdArg={$PlayerChosenProviderIdArg}" +
            "&$PlayerChosenStreamHandoffKeyArg={$PlayerChosenStreamHandoffKeyArg}"

    /**
     * Builds the player destination route. The launch payload mirrors the identity fields the
     * player session actually consumes; optional values travel as blank query parameters.
     */
    fun playerRoute(
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

    fun personDetailsRoute(personId: String, profileUrl: String? = null): String {
        return "$PersonDetailsRoute/${Uri.encode(personId.trim())}" +
            "?$PersonDetailsProfileUrlArg=${Uri.encode(profileUrl.orEmpty())}"
    }
}

package com.crispy.tv.ui.navigation


object AppRoutes {
    const val TopLevelScrollToTopRequestKey = "topLevelScrollToTopRequest"

    const val HomeRoute = "home"
    const val SearchRoute = "search"
    const val DiscoverRoute = "discover"
    const val LibraryRoute = "library"
    const val CalendarRoute = "calendar"
    const val SettingsRoute = "settings"

    // An overlay rather than a top-level destination: it is reached from home, takes no tab
    // position, and gets the same slide-in-from-right treatment search does.
    const val RandomWheelRoute = "randomwheel"

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
    const val PluginsSettingsRoute = "settings/plugins"
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
    const val PlayerTmdbIdArg = "tmdbId"
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

    val PlayerRoutePattern: String =
        "$PlayerRoute?$PlayerMediaTypeArg={$PlayerMediaTypeArg}" +
            "&$PlayerItemIdArg={$PlayerItemIdArg}" +
            "&$PlayerSeriesItemIdArg={$PlayerSeriesItemIdArg}" +
            "&$PlayerImdbIdArg={$PlayerImdbIdArg}" +
            "&$PlayerTmdbIdArg={$PlayerTmdbIdArg}" +
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

}

package com.crispy.tv.ui.navigation

import android.net.Uri
import com.crispy.tv.catalog.CatalogSectionRef

object AppRoutes {
    const val TopLevelScrollToTopRequestKey = "topLevelScrollToTopRequest"

    const val HomeRoute = "home"
    const val SearchRoute = "search"
    const val DiscoverRoute = "discover"
    const val LibraryRoute = "library"
    const val CalendarRoute = "calendar"
    const val SettingsRoute = "settings"

    const val HomeDetailsRoute = "home/details"
    const val HomeDetailsMediaKeyArg = "mediaKey"
    const val HomeDetailsMediaTypeArg = "mediaType"
    const val HomeDetailsHighlightEpisodeIdArg = "highlightEpisodeId"
    const val HomeDetailsAutoOpenEpisodeArg = "autoOpenEpisode"
    const val HomeDetailsRuntimeSeasonNumberArg = "runtimeSeasonNumber"
    const val HomeDetailsRuntimeEpisodeNumberArg = "runtimeEpisodeNumber"
    const val HomeDetailsRuntimeAbsoluteEpisodeArg = "runtimeAbsoluteEpisodeNumber"

    const val PersonDetailsRoute = "person/details"
    const val PersonDetailsPersonIdArg = "personId"

    const val PlaybackSettingsRoute = "settings/playback"
    const val AddonsSettingsRoute = "settings/addons"
    const val ProviderPortalRoute = "settings/providers"
    const val AccountsProfilesRoute = "settings/accounts"

    const val CatalogListRoute = "catalog"
    const val CatalogIdArg = "catalogId"
    const val CatalogTitleArg = "title"

    // Details: mediaKey is the public title identity route segment.
    val HomeDetailsRoutePattern: String =
        "$HomeDetailsRoute/{$HomeDetailsMediaTypeArg}/{$HomeDetailsMediaKeyArg}" +
            "?$HomeDetailsHighlightEpisodeIdArg={$HomeDetailsHighlightEpisodeIdArg}" +
            "&$HomeDetailsAutoOpenEpisodeArg={$HomeDetailsAutoOpenEpisodeArg}" +
            "&$HomeDetailsRuntimeSeasonNumberArg={$HomeDetailsRuntimeSeasonNumberArg}" +
            "&$HomeDetailsRuntimeEpisodeNumberArg={$HomeDetailsRuntimeEpisodeNumberArg}" +
            "&$HomeDetailsRuntimeAbsoluteEpisodeArg={$HomeDetailsRuntimeAbsoluteEpisodeArg}"
    val PersonDetailsRoutePattern: String =
        "$PersonDetailsRoute/{$PersonDetailsPersonIdArg}"
    val CatalogListRoutePattern: String =
        "$CatalogListRoute/{$CatalogIdArg}" +
            "?$CatalogTitleArg={$CatalogTitleArg}"

    fun homeDetailsRoute(
        mediaKey: String,
        mediaType: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        absoluteEpisodeNumber: Int? = null,
        highlightEpisodeId: String? = null,
        autoOpenEpisode: Boolean = false,
    ): String {
        return "$HomeDetailsRoute/${Uri.encode(mediaType.trim())}/${Uri.encode(mediaKey.trim())}" +
            "?$HomeDetailsHighlightEpisodeIdArg=${Uri.encode(highlightEpisodeId.orEmpty())}" +
            "&$HomeDetailsAutoOpenEpisodeArg=${autoOpenEpisode}" +
            "&$HomeDetailsRuntimeSeasonNumberArg=${seasonNumber?.toString().orEmpty()}" +
            "&$HomeDetailsRuntimeEpisodeNumberArg=${episodeNumber?.toString().orEmpty()}" +
            "&$HomeDetailsRuntimeAbsoluteEpisodeArg=${absoluteEpisodeNumber?.toString().orEmpty()}"
    }

    fun catalogListRoute(section: CatalogSectionRef): String {
        return "$CatalogListRoute/${Uri.encode(section.catalogId)}" +
            "?$CatalogTitleArg=${Uri.encode(section.displayTitle)}"
    }

    fun personDetailsRoute(personId: String): String {
        return "$PersonDetailsRoute/${Uri.encode(personId.trim())}"
    }
}

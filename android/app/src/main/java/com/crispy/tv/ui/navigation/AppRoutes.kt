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
    const val HomeDetailsItemIdArg = "itemId"
    const val HomeDetailsItemTypeArg = "itemType"
    const val HomeDetailsHighlightEpisodeIdArg = "highlightEpisodeId"
    const val HomeDetailsAutoOpenEpisodeArg = "autoOpenEpisode"
    const val HomeDetailsRuntimeSeasonNumberArg = "runtimeSeasonNumber"
    const val HomeDetailsRuntimeEpisodeNumberArg = "runtimeEpisodeNumber"
    const val HomeDetailsRuntimeAbsoluteEpisodeArg = "runtimeAbsoluteEpisodeNumber"
    const val HomeDetailsBackdropUrlArg = "backdropUrl"
    const val HomeDetailsLogoUrlArg = "logoUrl"
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

    // Details: itemId is the public title identity route segment.
    val HomeDetailsRoutePattern: String =
        "$HomeDetailsRoute/{$HomeDetailsItemTypeArg}/{$HomeDetailsItemIdArg}" +
            "?$HomeDetailsHighlightEpisodeIdArg={$HomeDetailsHighlightEpisodeIdArg}" +
            "&$HomeDetailsAutoOpenEpisodeArg={$HomeDetailsAutoOpenEpisodeArg}" +
            "&$HomeDetailsRuntimeSeasonNumberArg={$HomeDetailsRuntimeSeasonNumberArg}" +
            "&$HomeDetailsRuntimeEpisodeNumberArg={$HomeDetailsRuntimeEpisodeNumberArg}" +
            "&$HomeDetailsRuntimeAbsoluteEpisodeArg={$HomeDetailsRuntimeAbsoluteEpisodeArg}" +
            "&$HomeDetailsBackdropUrlArg={$HomeDetailsBackdropUrlArg}" +
            "&$HomeDetailsLogoUrlArg={$HomeDetailsLogoUrlArg}" +
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
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        absoluteEpisodeNumber: Int? = null,
        highlightEpisodeId: String? = null,
        autoOpenEpisode: Boolean = false,
        backdropUrl: String? = null,
        logoUrl: String? = null,
        sharedElementKey: String? = null,
    ): String {
        return "$HomeDetailsRoute/${Uri.encode(itemType.trim())}/${Uri.encode(itemId.trim())}" +
            "?$HomeDetailsHighlightEpisodeIdArg=${Uri.encode(highlightEpisodeId.orEmpty())}" +
            "&$HomeDetailsAutoOpenEpisodeArg=${autoOpenEpisode}" +
            "&$HomeDetailsRuntimeSeasonNumberArg=${seasonNumber?.toString().orEmpty()}" +
            "&$HomeDetailsRuntimeEpisodeNumberArg=${episodeNumber?.toString().orEmpty()}" +
            "&$HomeDetailsRuntimeAbsoluteEpisodeArg=${absoluteEpisodeNumber?.toString().orEmpty()}" +
            "&$HomeDetailsBackdropUrlArg=${Uri.encode(backdropUrl.orEmpty())}" +
            "&$HomeDetailsLogoUrlArg=${Uri.encode(logoUrl.orEmpty())}" +
            "&$HomeDetailsSharedElementKeyArg=${Uri.encode(sharedElementKey.orEmpty())}"
    }

    fun catalogListRoute(section: CatalogSectionRef): String {
        return "$CatalogListRoute/${Uri.encode(section.catalogId)}" +
            "?$CatalogTitleArg=${Uri.encode(section.displayTitle)}"
    }

    fun personDetailsRoute(personId: String, profileUrl: String? = null): String {
        return "$PersonDetailsRoute/${Uri.encode(personId.trim())}" +
            "?$PersonDetailsProfileUrlArg=${Uri.encode(profileUrl.orEmpty())}"
    }
}

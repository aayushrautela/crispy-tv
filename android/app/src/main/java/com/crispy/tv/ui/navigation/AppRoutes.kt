package com.crispy.tv.ui.navigation

import android.net.Uri
import com.crispy.tv.catalog.CatalogSectionRef
import java.util.Locale

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
    const val HomeDetailsMediaTypeArg = "mediaType"
    const val HomeDetailsHighlightEpisodeIdArg = "highlightEpisodeId"
    const val HomeDetailsAutoOpenEpisodeArg = "autoOpenEpisode"
    const val HomeDetailsRuntimeProviderArg = "runtimeProvider"
    const val HomeDetailsRuntimeProviderIdArg = "runtimeProviderId"
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

    // Details: type + id are required route segments.
    val HomeDetailsRoutePattern: String =
        "$HomeDetailsRoute/{$HomeDetailsMediaTypeArg}/{$HomeDetailsItemIdArg}" +
            "?$HomeDetailsHighlightEpisodeIdArg={$HomeDetailsHighlightEpisodeIdArg}" +
            "&$HomeDetailsAutoOpenEpisodeArg={$HomeDetailsAutoOpenEpisodeArg}" +
            "&$HomeDetailsRuntimeProviderArg={$HomeDetailsRuntimeProviderArg}" +
            "&$HomeDetailsRuntimeProviderIdArg={$HomeDetailsRuntimeProviderIdArg}" +
            "&$HomeDetailsRuntimeSeasonNumberArg={$HomeDetailsRuntimeSeasonNumberArg}" +
            "&$HomeDetailsRuntimeEpisodeNumberArg={$HomeDetailsRuntimeEpisodeNumberArg}" +
            "&$HomeDetailsRuntimeAbsoluteEpisodeArg={$HomeDetailsRuntimeAbsoluteEpisodeArg}"
    val PersonDetailsRoutePattern: String =
        "$PersonDetailsRoute/{$PersonDetailsPersonIdArg}"
    val CatalogListRoutePattern: String =
        "$CatalogListRoute/{$CatalogIdArg}" +
            "?$CatalogTitleArg={$CatalogTitleArg}"

    fun homeDetailsRoute(
        itemId: String,
        mediaType: String,
        highlightEpisodeId: String? = null,
        autoOpenEpisode: Boolean = false,
    ): String {
        val normalizedType =
            when (mediaType.trim().lowercase(Locale.US)) {
                "movie" -> "movie"
                "series", "show", "tv" -> "series"
                else -> mediaType.trim().lowercase(Locale.US)
        }
        return "$HomeDetailsRoute/${Uri.encode(normalizedType)}/${Uri.encode(itemId.trim())}" +
            "?$HomeDetailsHighlightEpisodeIdArg=${Uri.encode(highlightEpisodeId.orEmpty())}" +
            "&$HomeDetailsAutoOpenEpisodeArg=${autoOpenEpisode}" +
            "&$HomeDetailsRuntimeProviderArg=" +
            "&$HomeDetailsRuntimeProviderIdArg=" +
            "&$HomeDetailsRuntimeSeasonNumberArg=" +
            "&$HomeDetailsRuntimeEpisodeNumberArg=" +
            "&$HomeDetailsRuntimeAbsoluteEpisodeArg="
    }

    fun runtimeDetailsRoute(
        provider: String,
        providerId: String,
        mediaType: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        absoluteEpisodeNumber: Int? = null,
        highlightEpisodeId: String? = null,
        autoOpenEpisode: Boolean = false,
    ): String {
        val normalizedType = mediaType.trim().lowercase(Locale.US)
        val runtimeKey = "$normalizedType:${provider.trim()}:${providerId.trim()}"
        return "$HomeDetailsRoute/${Uri.encode(normalizedType)}/${Uri.encode(runtimeKey)}" +
            "?$HomeDetailsHighlightEpisodeIdArg=${Uri.encode(highlightEpisodeId.orEmpty())}" +
            "&$HomeDetailsAutoOpenEpisodeArg=$autoOpenEpisode" +
            "&$HomeDetailsRuntimeProviderArg=${Uri.encode(provider.trim())}" +
            "&$HomeDetailsRuntimeProviderIdArg=${Uri.encode(providerId.trim())}" +
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

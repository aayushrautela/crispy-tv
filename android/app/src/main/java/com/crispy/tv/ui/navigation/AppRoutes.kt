package com.crispy.tv.ui.navigation

import android.net.Uri
import com.crispy.tv.catalog.CatalogSectionRef
import java.util.Locale

object AppRoutes {
    const val HomeRoute = "home"
    const val SearchRoute = "search"
    const val DiscoverRoute = "discover"
    const val LibraryRoute = "library"
    const val SettingsRoute = "settings"

    const val HomeDetailsRoute = "home/details"
    const val HomeDetailsItemIdArg = "itemId"
    const val HomeDetailsMediaTypeArg = "mediaType"
    const val PlayerRoute = "player"
    const val PlayerUrlArg = "url"
    const val PlayerTitleArg = "title"

    const val LabsRoute = "labs"
    const val PlaybackSettingsRoute = "settings/playback"
    const val HomeScreenSettingsRoute = "settings/home"
    const val AddonsSettingsRoute = "settings/addons"
    const val AiInsightsSettingsRoute = "settings/ai"
    const val ProviderPortalRoute = "settings/providers"

    const val CatalogListRoute = "catalog"
    const val CatalogMediaTypeArg = "mediaType"
    const val CatalogIdArg = "catalogId"
    const val CatalogTitleArg = "title"
    const val CatalogAddonIdArg = "addonId"
    const val CatalogBaseUrlArg = "baseUrl"
    const val CatalogQueryArg = "query"

    // Details: type + id are required route segments.
    val HomeDetailsRoutePattern: String =
        "$HomeDetailsRoute/{$HomeDetailsMediaTypeArg}/{$HomeDetailsItemIdArg}"
    val PlayerRoutePattern: String =
        "$PlayerRoute?$PlayerUrlArg={$PlayerUrlArg}&$PlayerTitleArg={$PlayerTitleArg}"
    val CatalogListRoutePattern: String =
        "$CatalogListRoute/{$CatalogMediaTypeArg}/{$CatalogIdArg}" +
            "?$CatalogTitleArg={$CatalogTitleArg}" +
            "&$CatalogAddonIdArg={$CatalogAddonIdArg}" +
            "&$CatalogBaseUrlArg={$CatalogBaseUrlArg}" +
            "&$CatalogQueryArg={$CatalogQueryArg}"

    fun homeDetailsRoute(itemId: String, mediaType: String): String {
        val normalizedType =
            when (mediaType.trim().lowercase(Locale.US)) {
                "movie" -> "movie"
                "series", "show", "tv" -> "series"
                else -> mediaType.trim().lowercase(Locale.US)
            }
        return "$HomeDetailsRoute/${Uri.encode(normalizedType)}/${Uri.encode(itemId.trim())}"
    }

    fun playerRoute(url: String, title: String): String {
        return "$PlayerRoute?$PlayerUrlArg=${Uri.encode(url)}&$PlayerTitleArg=${Uri.encode(title)}"
    }

    fun catalogListRoute(section: CatalogSectionRef): String {
        val query = section.encodedAddonQuery ?: ""
        return "$CatalogListRoute/${Uri.encode(section.mediaType)}/${Uri.encode(section.catalogId)}" +
            "?$CatalogTitleArg=${Uri.encode(section.title)}" +
            "&$CatalogAddonIdArg=${Uri.encode(section.addonId)}" +
            "&$CatalogBaseUrlArg=${Uri.encode(section.baseUrl)}" +
            "&$CatalogQueryArg=${Uri.encode(query)}"
    }
}

package com.crispy.rewrite.ui.navigation

import android.net.Uri
import com.crispy.rewrite.catalog.CatalogSectionRef

object AppRoutes {
    const val HomeRoute = "home"
    const val SearchRoute = "search"
    const val DiscoverRoute = "discover"
    const val LibraryRoute = "library"
    const val SettingsRoute = "settings"

    const val HomeDetailsRoute = "home/details"
    const val HomeDetailsItemIdArg = "itemId"

    const val LabsRoute = "labs"
    const val PlaybackSettingsRoute = "settings/playback"
    const val HomeScreenSettingsRoute = "settings/home"
    const val AddonsSettingsRoute = "settings/addons"
    const val ProviderPortalRoute = "settings/providers"

    const val CatalogListRoute = "catalog"
    const val CatalogMediaTypeArg = "mediaType"
    const val CatalogIdArg = "catalogId"
    const val CatalogTitleArg = "title"
    const val CatalogAddonIdArg = "addonId"
    const val CatalogBaseUrlArg = "baseUrl"
    const val CatalogQueryArg = "query"

    val HomeDetailsRoutePattern: String = "$HomeDetailsRoute/{$HomeDetailsItemIdArg}"
    val CatalogListRoutePattern: String =
        "$CatalogListRoute/{$CatalogMediaTypeArg}/{$CatalogIdArg}" +
            "?$CatalogTitleArg={$CatalogTitleArg}" +
            "&$CatalogAddonIdArg={$CatalogAddonIdArg}" +
            "&$CatalogBaseUrlArg={$CatalogBaseUrlArg}" +
            "&$CatalogQueryArg={$CatalogQueryArg}"

    fun homeDetailsRoute(itemId: String): String = "$HomeDetailsRoute/${Uri.encode(itemId)}"

    fun catalogListRoute(section: CatalogSectionRef): String {
        val query = section.encodedAddonQuery ?: ""
        return "$CatalogListRoute/${Uri.encode(section.mediaType)}/${Uri.encode(section.catalogId)}" +
            "?$CatalogTitleArg=${Uri.encode(section.title)}" +
            "&$CatalogAddonIdArg=${Uri.encode(section.addonId)}" +
            "&$CatalogBaseUrlArg=${Uri.encode(section.baseUrl)}" +
            "&$CatalogQueryArg=${Uri.encode(query)}"
    }
}

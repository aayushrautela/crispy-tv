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

    const val PersonDetailsRoute = "person/details"
    const val PersonDetailsPersonIdArg = "personId"

    const val PlaybackSettingsRoute = "settings/playback"
    const val AddonsSettingsRoute = "settings/addons"
    const val AiInsightsSettingsRoute = "settings/ai"
    const val ProviderPortalRoute = "settings/providers"
    const val AccountsProfilesRoute = "settings/accounts"

    const val CatalogListRoute = "catalog"
    const val CatalogIdArg = "catalogId"
    const val CatalogTitleArg = "title"

    // Details: type + id are required route segments.
    val HomeDetailsRoutePattern: String =
        "$HomeDetailsRoute/{$HomeDetailsMediaTypeArg}/{$HomeDetailsItemIdArg}"
    val PersonDetailsRoutePattern: String =
        "$PersonDetailsRoute/{$PersonDetailsPersonIdArg}"
    val CatalogListRoutePattern: String =
        "$CatalogListRoute/{$CatalogIdArg}" +
            "?$CatalogTitleArg={$CatalogTitleArg}"

    fun homeDetailsRoute(itemId: String, mediaType: String): String {
        val normalizedType =
            when (mediaType.trim().lowercase(Locale.US)) {
                "movie" -> "movie"
                "series", "show", "tv" -> "series"
                else -> mediaType.trim().lowercase(Locale.US)
            }
        return "$HomeDetailsRoute/${Uri.encode(normalizedType)}/${Uri.encode(itemId.trim())}"
    }

    fun catalogListRoute(section: CatalogSectionRef): String {
        return "$CatalogListRoute/${Uri.encode(section.catalogId)}" +
            "?$CatalogTitleArg=${Uri.encode(section.title)}"
    }

    fun personDetailsRoute(personId: String): String {
        return "$PersonDetailsRoute/${Uri.encode(personId.trim())}"
    }
}

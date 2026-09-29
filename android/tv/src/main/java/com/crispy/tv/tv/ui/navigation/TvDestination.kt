package com.crispy.tv.tv.ui.navigation

import com.crispy.tv.ui.resources.Res
import com.crispy.tv.ui.resources.ic_home_filled
import com.crispy.tv.ui.resources.ic_list_filled
import com.crispy.tv.ui.resources.ic_search_filled
import com.crispy.tv.ui.resources.ic_settings_filled
import org.jetbrains.compose.resources.DrawableResource

enum class TvDestination(
    val route: String,
    val label: String,
    val icon: DrawableResource,
) {
    Home("home", "Home", Res.drawable.ic_home_filled),
    Search("search", "Search", Res.drawable.ic_search_filled),
    Library("library", "Library", Res.drawable.ic_list_filled),
    Settings("settings", "Settings", Res.drawable.ic_settings_filled);

    companion object {
        val default = Home

        fun fromRoute(route: String?): TvDestination =
            entries.firstOrNull { it.route == route } ?: default
    }
}

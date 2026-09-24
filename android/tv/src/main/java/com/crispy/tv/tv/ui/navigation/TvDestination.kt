package com.crispy.tv.tv.ui.navigation

import androidx.annotation.DrawableRes
import com.crispy.tv.ui.assets.R

enum class TvDestination(
    val route: String,
    val label: String,
    @DrawableRes val icon: Int,
) {
    Home("home", "Home", R.drawable.ic_home_filled),
    Search("search", "Search", R.drawable.ic_search_filled),
    Library("library", "Library", R.drawable.ic_list_filled),
    Settings("settings", "Settings", R.drawable.ic_settings_filled);

    companion object {
        val default = Home

        fun fromRoute(route: String?): TvDestination =
            entries.firstOrNull { it.route == route } ?: default
    }
}

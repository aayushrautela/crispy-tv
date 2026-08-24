package com.crispy.tv.tv.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class TvDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Home("home", "Home", Icons.Filled.Home),
    Search("search", "Search", Icons.Filled.Search),
    Library("library", "Library", Icons.Filled.List),
    Settings("settings", "Settings", Icons.Filled.Settings);

    companion object {
        val default = Home

        fun fromRoute(route: String?): TvDestination =
            entries.firstOrNull { it.route == route } ?: default
    }
}

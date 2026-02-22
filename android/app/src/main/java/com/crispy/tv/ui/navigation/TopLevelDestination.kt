package com.crispy.tv.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector

enum class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val showInBottomBar: Boolean = true
) {
    Home(route = AppRoutes.HomeRoute, label = "Home", icon = Icons.Outlined.Home),
    Search(route = AppRoutes.SearchRoute, label = "Search", icon = Icons.Outlined.Search),
    Discover(route = AppRoutes.DiscoverRoute, label = "Discover", icon = Icons.Outlined.Explore),
    Library(route = AppRoutes.LibraryRoute, label = "Library", icon = Icons.Outlined.VideoLibrary),
    Settings(route = AppRoutes.SettingsRoute, label = "Settings", icon = Icons.Outlined.Settings)
}

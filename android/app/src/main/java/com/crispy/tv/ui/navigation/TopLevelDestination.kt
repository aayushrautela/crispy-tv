package com.crispy.tv.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector

enum class TopLevelDestination(
    val route: String,
    val label: String,
    val inactiveIcon: ImageVector,
    val activeIcon: ImageVector,
) {
    Home(
        route = AppRoutes.HomeRoute,
        label = "Home",
        inactiveIcon = Icons.Outlined.Home,
        activeIcon = Icons.Filled.Home,
    ),
    Search(
        route = AppRoutes.SearchRoute,
        label = "Search",
        inactiveIcon = Icons.Outlined.Search,
        activeIcon = Icons.Filled.Search,
    ),
    Discover(
        route = AppRoutes.DiscoverRoute,
        label = "Discover",
        inactiveIcon = Icons.Outlined.Explore,
        activeIcon = Icons.Filled.Explore,
    ),
    Library(
        route = AppRoutes.LibraryRoute,
        label = "Library",
        inactiveIcon = Icons.Outlined.VideoLibrary,
        activeIcon = Icons.Filled.VideoLibrary,
    ),
    Settings(
        route = AppRoutes.SettingsRoute,
        label = "Settings",
        inactiveIcon = Icons.Outlined.Settings,
        activeIcon = Icons.Filled.Settings,
    ),
}

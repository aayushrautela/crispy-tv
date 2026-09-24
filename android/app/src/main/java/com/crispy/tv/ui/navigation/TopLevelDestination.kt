package com.crispy.tv.ui.navigation

import androidx.annotation.DrawableRes
import com.crispy.tv.ui.assets.R

enum class TopLevelDestination(
    val route: String,
    val label: String,
    @DrawableRes val inactiveIcon: Int,
    @DrawableRes val activeIcon: Int,
) {
    Home(
        route = AppRoutes.HomeRoute,
        label = "Home",
        inactiveIcon = R.drawable.ic_home,
        activeIcon = R.drawable.ic_home_filled,
    ),
    Discover(
        route = AppRoutes.DiscoverRoute,
        label = "Discover",
        inactiveIcon = R.drawable.ic_explore,
        activeIcon = R.drawable.ic_explore_filled,
    ),
    Library(
        route = AppRoutes.LibraryRoute,
        label = "Library",
        inactiveIcon = R.drawable.ic_video_library,
        activeIcon = R.drawable.ic_video_library_filled,
    ),
}

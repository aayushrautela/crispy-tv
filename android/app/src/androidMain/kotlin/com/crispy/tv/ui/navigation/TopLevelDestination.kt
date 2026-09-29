package com.crispy.tv.ui.navigation

import com.crispy.tv.ui.resources.Res
import com.crispy.tv.ui.resources.ic_explore
import com.crispy.tv.ui.resources.ic_explore_filled
import com.crispy.tv.ui.resources.ic_home
import com.crispy.tv.ui.resources.ic_home_filled
import com.crispy.tv.ui.resources.ic_video_library
import com.crispy.tv.ui.resources.ic_video_library_filled
import org.jetbrains.compose.resources.DrawableResource

enum class TopLevelDestination(
    val route: String,
    val label: String,
    val inactiveIcon: DrawableResource,
    val activeIcon: DrawableResource,
) {
    Home(
        route = AppRoutes.HomeRoute,
        label = "Home",
        inactiveIcon = Res.drawable.ic_home,
        activeIcon = Res.drawable.ic_home_filled,
    ),
    Discover(
        route = AppRoutes.DiscoverRoute,
        label = "Discover",
        inactiveIcon = Res.drawable.ic_explore,
        activeIcon = Res.drawable.ic_explore_filled,
    ),
    Library(
        route = AppRoutes.LibraryRoute,
        label = "Library",
        inactiveIcon = Res.drawable.ic_video_library,
        activeIcon = Res.drawable.ic_video_library_filled,
    ),
}

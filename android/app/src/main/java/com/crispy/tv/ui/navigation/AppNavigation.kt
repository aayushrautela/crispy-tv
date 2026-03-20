package com.crispy.tv.ui.navigation

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

internal fun isRouteSelected(
    currentRoute: String?,
    screenRoute: String,
    topLevelRoutes: Set<String>,
): Boolean {
    if (currentRoute == null) {
        return false
    }
    if (currentRoute == screenRoute) {
        return true
    }
    return topLevelRoutes.contains(screenRoute) && currentRoute.startsWith("$screenRoute/")
}

@Composable
fun AppNavigationBar(
    navigationItems: List<TopLevelDestination>,
    currentRoute: String?,
    onItemClick: (TopLevelDestination, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val topLevelRoutes = remember(navigationItems) { navigationItems.map { it.route }.toSet() }
    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        navigationItems.forEach { destination ->
            val isSelected = remember(currentRoute, destination.route) {
                isRouteSelected(currentRoute, destination.route, topLevelRoutes)
            }
            NavigationBarItem(
                selected = isSelected,
                onClick = { onItemClick(destination, isSelected) },
                icon = {
                    Icon(
                        imageVector = if (isSelected) destination.activeIcon else destination.inactiveIcon,
                        contentDescription = destination.label,
                    )
                },
                label = { Text(destination.label) },
            )
        }
    }
}

@Composable
fun AppNavigationRail(
    navigationItems: List<TopLevelDestination>,
    currentRoute: String?,
    onItemClick: (TopLevelDestination, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val topLevelRoutes = remember(navigationItems) { navigationItems.map { it.route }.toSet() }
    NavigationRail(
        modifier = modifier.fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Spacer(modifier = Modifier.weight(1f))
        navigationItems.forEach { destination ->
            val isSelected = remember(currentRoute, destination.route) {
                isRouteSelected(currentRoute, destination.route, topLevelRoutes)
            }
            NavigationRailItem(
                selected = isSelected,
                onClick = { onItemClick(destination, isSelected) },
                icon = {
                    Icon(
                        imageVector = if (isSelected) destination.activeIcon else destination.inactiveIcon,
                        contentDescription = destination.label,
                    )
                },
            )
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

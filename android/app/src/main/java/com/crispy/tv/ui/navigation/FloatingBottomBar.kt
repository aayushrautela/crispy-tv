package com.crispy.tv.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val FloatingBarHorizontalMargin = 16.dp
private val FloatingBarPillCornerRadius = 24.dp
private val FloatingBarVerticalPadding = 12.dp
private val FloatingBarElevation = 3.dp
private val FloatingBarCircleDiameter = 56.dp
private val FloatingBarItemIconSize = 24.dp

@Composable
internal fun FloatingBottomBar(
    items: List<TopLevelDestination>,
    currentRoute: String?,
    onItemClick: (TopLevelDestination, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val topLevelRoutes = remember(items) { items.map { it.route }.toSet() }
    val searchItem = items.last()

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FloatingBarHorizontalMargin),
            horizontalArrangement = Arrangement.spacedBy(FloatingBarHorizontalMargin),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(FloatingBarPillCornerRadius),
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = FloatingBarElevation,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    items.dropLast(1).forEach { destination ->
                        FloatingPillItem(
                            destination = destination,
                            currentRoute = currentRoute,
                            topLevelRoutes = topLevelRoutes,
                            onItemClick = onItemClick,
                        )
                    }
                }
            }

            FloatingSearchButton(
                destination = searchItem,
                currentRoute = currentRoute,
                topLevelRoutes = topLevelRoutes,
                onItemClick = onItemClick,
            )
        }

        Spacer(
            Modifier
                .windowInsetsBottomHeight(WindowInsets.navigationBars)
                .fillMaxWidth(),
        )
    }
}

@Composable
private fun FloatingPillItem(
    destination: TopLevelDestination,
    currentRoute: String?,
    topLevelRoutes: Set<String>,
    onItemClick: (TopLevelDestination, Boolean) -> Unit,
) {
    val isSelected = remember(currentRoute, destination.route) {
        isRouteSelected(currentRoute, destination.route, topLevelRoutes)
    }
    val tint = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = { onItemClick(destination, isSelected) },
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = 12.dp,
                vertical = FloatingBarVerticalPadding,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = if (isSelected) destination.activeIcon else destination.inactiveIcon,
                contentDescription = destination.label,
                tint = tint,
                modifier = Modifier.size(FloatingBarItemIconSize),
            )
            Text(
                text = destination.label,
                style = MaterialTheme.typography.labelMedium,
                color = tint,
            )
        }
    }
}

@Composable
private fun FloatingSearchButton(
    destination: TopLevelDestination,
    currentRoute: String?,
    topLevelRoutes: Set<String>,
    onItemClick: (TopLevelDestination, Boolean) -> Unit,
) {
    val isSelected = remember(currentRoute, destination.route) {
        isRouteSelected(currentRoute, destination.route, topLevelRoutes)
    }
    val tint = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = { onItemClick(destination, isSelected) },
        modifier = Modifier.size(FloatingBarCircleDiameter),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = FloatingBarElevation,
    ) {
        Icon(
            imageVector = if (isSelected) destination.activeIcon else destination.inactiveIcon,
            contentDescription = destination.label,
            tint = tint,
            modifier = Modifier.size(FloatingBarItemIconSize),
        )
    }
}

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

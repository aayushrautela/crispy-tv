package com.crispy.tv.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Outer capsule
private val BarHeight = 60.dp
private val BarCornerRadius = 30.dp             // BarHeight / 2 → capsule
private val BarInnerPadding = 6.dp
private val BarElevation = 6.dp
private val BarBottomMargin = 16.dp

// Inner selected-chip (concentric capsule)
private val ChipHeight = 48.dp                  // BarHeight − 2 × BarInnerPadding
private val ChipCornerRadius = 24.dp            // ChipHeight / 2 → capsule

// Trailing circle
private val CircleDiameter = 60.dp
private val IconSize = 18.dp

private val AnimDuration = 250

@Composable
internal fun FloatingBottomBar(
    destinations: List<TopLevelDestination>,
    currentRoute: String?,
    onDestinationClick: (TopLevelDestination, Boolean) -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val topLevelRoutes = remember(destinations) { destinations.map { it.route }.toSet() }

    Row(
        modifier = modifier
            .navigationBarsPadding()
            .padding(bottom = BarBottomMargin),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Navigation capsule pill
        Surface(
            modifier = Modifier.height(BarHeight),
            shape = RoundedCornerShape(BarCornerRadius),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = BarElevation,
        ) {
            Row(
                modifier = Modifier.padding(BarInnerPadding),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                destinations.forEach { destination ->
                    BarChip(
                        destination = destination,
                        currentRoute = currentRoute,
                        topLevelRoutes = topLevelRoutes,
                        onClick = onDestinationClick,
                    )
                }
            }
        }

        // Search action button (not a navigation tab)
        Surface(
            onClick = onSearchClick,
            modifier = Modifier.size(CircleDiameter),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = BarElevation,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun BarChip(
    destination: TopLevelDestination,
    currentRoute: String?,
    topLevelRoutes: Set<String>,
    onClick: (TopLevelDestination, Boolean) -> Unit,
) {
    val isSelected = remember(currentRoute, destination.route) {
        isRouteSelected(currentRoute, destination.route, topLevelRoutes)
    }
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(AnimDuration),
        label = "chipContent",
    )
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        animationSpec = tween(AnimDuration),
        label = "chipBg",
    )

    Surface(
        onClick = { onClick(destination, isSelected) },
        modifier = Modifier.height(ChipHeight),
        shape = RoundedCornerShape(ChipCornerRadius),
        color = backgroundColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            AnimatedVisibility(
                visible = isSelected,
                enter = expandHorizontally(tween(AnimDuration), expandFrom = Alignment.Start),
                exit = shrinkHorizontally(tween(200), shrinkTowards = Alignment.Start),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = destination.activeIcon,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(IconSize),
                    )
                    Spacer(Modifier.width(6.dp))
                }
            }
            Text(
                text = destination.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = contentColor,
            )
        }
    }
}

internal fun isRouteSelected(
    currentRoute: String?,
    screenRoute: String,
    topLevelRoutes: Set<String>,
): Boolean {
    if (currentRoute == null) return false
    if (currentRoute == screenRoute) return true
    return topLevelRoutes.contains(screenRoute) && currentRoute.startsWith("$screenRoute/")
}

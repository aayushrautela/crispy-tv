package com.crispy.tv.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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

private val FloatingBarHorizontalMargin = 16.dp
private val FloatingBarPillCornerRadius = 24.dp
private val FloatingBarPillInnerPadding = 8.dp
private val FloatingBarChipCornerRadius = 16.dp // outer − inner padding, for concentric nesting
private val FloatingBarPillHeight = 48.dp
private val FloatingBarElevation = 3.dp
private val FloatingBarCircleDiameter = 48.dp
private val FloatingBarIconSize = 18.dp

@Composable
internal fun FloatingBottomBar(
    items: List<TopLevelDestination>,
    currentRoute: String?,
    onItemClick: (TopLevelDestination, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val topLevelRoutes = remember(items) { items.map { it.route }.toSet() }
    val pillItems = items.dropLast(1)
    val trailingItem = items.last()

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FloatingBarHorizontalMargin),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Pill containing text labels — selected tab gets an inline icon
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(FloatingBarPillHeight),
                shape = RoundedCornerShape(FloatingBarPillCornerRadius),
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = FloatingBarElevation,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = FloatingBarPillInnerPadding),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    pillItems.forEach { destination ->
                        FloatingPillTextItem(
                            destination = destination,
                            currentRoute = currentRoute,
                            topLevelRoutes = topLevelRoutes,
                            onItemClick = onItemClick,
                        )
                    }
                }
            }

            // Trailing circular button (Search)
            FloatingCircleButton(
                destination = trailingItem,
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
private fun FloatingPillTextItem(
    destination: TopLevelDestination,
    currentRoute: String?,
    topLevelRoutes: Set<String>,
    onItemClick: (TopLevelDestination, Boolean) -> Unit,
) {
    val isSelected = remember(currentRoute, destination.route) {
        isRouteSelected(currentRoute, destination.route, topLevelRoutes)
    }
    val textColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(200),
        label = "pillTextColor",
    )
    val chipBackground by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        animationSpec = tween(250),
        label = "chipBackground",
    )

    Surface(
        onClick = { onItemClick(destination, isSelected) },
        color = chipBackground,
        shape = RoundedCornerShape(FloatingBarChipCornerRadius),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            AnimatedVisibility(
                visible = isSelected,
                enter = fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.6f),
                exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.6f),
            ) {
                Row {
                    Icon(
                        imageVector = destination.activeIcon,
                        contentDescription = null,
                        tint = textColor,
                        modifier = Modifier.size(FloatingBarIconSize),
                    )
                    Spacer(Modifier.width(6.dp))
                }
            }
            Text(
                text = destination.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = textColor,
            )
        }
    }
}

@Composable
private fun FloatingCircleButton(
    destination: TopLevelDestination,
    currentRoute: String?,
    topLevelRoutes: Set<String>,
    onItemClick: (TopLevelDestination, Boolean) -> Unit,
) {
    val isSelected = remember(currentRoute, destination.route) {
        isRouteSelected(currentRoute, destination.route, topLevelRoutes)
    }
    val tint by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(200),
        label = "circleButtonTint",
    )
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
            modifier = Modifier
                .padding(12.dp)
                .size(FloatingBarIconSize),
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

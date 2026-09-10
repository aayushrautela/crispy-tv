package com.crispy.tv.tv.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.crispy.tv.tv.ui.navigation.TvDestination
import kotlinx.coroutines.delay

private val SidebarWidth = 200.dp
private val SidebarCollapsedWidth = 76.dp
private val SidebarCollapseDelayMs = 2_500L

@Composable
fun SidebarNavigation(
    selected: TvDestination,
    onSelect: (TvDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(true) }
    LaunchedEffect(focused) {
        if (focused) {
            expanded = true
        } else {
            delay(SidebarCollapseDelayMs)
            expanded = false
        }
    }
    val width by animateDpAsState(
        targetValue = if (expanded) SidebarWidth else SidebarCollapsedWidth,
        animationSpec = tween(durationMillis = 220),
        label = "sidebarWidth",
    )

    Column(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.45f))
            .padding(vertical = 24.dp)
            .onFocusChanged { focused = it.hasFocus },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        TvDestination.entries.forEach { destination ->
            SidebarItem(
                icon = destination.icon,
                label = destination.label,
                selected = destination == selected,
                expanded = expanded,
                onClick = { onSelect(destination) },
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun SidebarItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.04f else 1f,
        label = "sidebarScale",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (expanded) 20.dp else 0.dp)
            .scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    focused -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    selected -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
                },
            )
            .then(
                if (focused) {
                    Modifier.border(
                        BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                        RoundedCornerShape(14.dp),
                    )
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected || focused) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (expanded) {
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected || focused) {
                    MaterialTheme.colorScheme.onBackground
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

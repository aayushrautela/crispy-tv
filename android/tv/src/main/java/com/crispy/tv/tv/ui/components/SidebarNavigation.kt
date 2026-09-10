package com.crispy.tv.tv.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.crispy.tv.tv.ui.navigation.TvDestination
import kotlinx.coroutines.delay

private val SidebarExpandedWidth = 200.dp
private val SidebarCollapsedWidth = 76.dp
private const val SidebarCollapseDelayMs = 95L
private const val SidebarFocusTransferDelayMs = 110L
private const val PanelInDurationMs = 280
private const val PanelOutDurationMs = 200
private val SidebarItemShape = RoundedCornerShape(14.dp)

/** Requester attached to the main content so the sidebar can hand focus back after dismissing. */
val LocalTvContentFocusRequester = staticCompositionLocalOf<FocusRequester?> { null }

/**
 * Single source of truth for sidebar visibility. The sidebar is a fixed-width
 * overlay: opening and closing are pure graphicsLayer transforms, so content
 * underneath never reflows.
 */
@Stable
class TvSidebarState internal constructor() {
    var isExpanded by mutableStateOf(false)
        internal set
    internal var collapsePending by mutableStateOf(false)
    internal var pendingFocusTransfer by mutableStateOf(false)
    internal var pendingContentFocusTransfer by mutableStateOf(false)

    fun requestOpen() {
        collapsePending = false
        pendingFocusTransfer = true
        isExpanded = true
    }

    fun requestDismiss(transferFocusToContent: Boolean = false) {
        if (transferFocusToContent) {
            pendingContentFocusTransfer = true
        }
        collapsePending = true
    }

    internal fun onPanelFocusGained() {
        collapsePending = false
        isExpanded = true
    }

    internal fun reset() {
        isExpanded = false
        collapsePending = false
        pendingFocusTransfer = false
        pendingContentFocusTransfer = false
    }
}

@Composable
fun rememberTvSidebarState(): TvSidebarState = remember { TvSidebarState() }

@Composable
fun SidebarNavigation(
    state: TvSidebarState,
    selected: TvDestination,
    onSelect: (TvDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val itemRequesters = remember {
        TvDestination.entries.associateWith { FocusRequester() }
    }

    DisposableEffect(Unit) {
        onDispose { state.reset() }
    }

    LaunchedEffect(state.collapsePending) {
        if (!state.collapsePending) return@LaunchedEffect
        delay(SidebarCollapseDelayMs)
        if (state.collapsePending) {
            state.isExpanded = false
            state.collapsePending = false
        }
    }

    // Hand focus from the icon rail to the matching panel item after expanding.
    LaunchedEffect(state.pendingFocusTransfer) {
        if (!state.pendingFocusTransfer) return@LaunchedEffect
        delay(SidebarFocusTransferDelayMs)
        runCatching { itemRequesters[selected]?.requestFocus() }
        state.pendingFocusTransfer = false
    }

    val contentRequester = LocalTvContentFocusRequester.current

    // Hand focus to the content after the panel finished dismissing.
    LaunchedEffect(
        state.pendingContentFocusTransfer,
        state.isExpanded,
        state.collapsePending,
    ) {
        if (!state.pendingContentFocusTransfer || state.isExpanded || state.collapsePending) {
            return@LaunchedEffect
        }
        withFrameNanos {}
        if (contentRequester != null) {
            runCatching { contentRequester.requestFocus() }
        }
        state.pendingContentFocusTransfer = false
    }

    val transition = updateTransition(targetState = state.isExpanded, label = "sidebar")
    val expandProgress by transition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(durationMillis = PanelInDurationMs, easing = FastOutSlowInEasing)
            } else {
                tween(durationMillis = PanelOutDurationMs, easing = LinearOutSlowInEasing)
            }
        },
        label = "sidebarExpandProgress",
    ) { isExpanded -> if (isExpanded) 1f else 0f }

    Box(modifier = modifier.fillMaxHeight()) {
        SidebarRail(
            selected = selected,
            onSelect = onSelect,
            expandProgress = expandProgress,
            canTakeFocus = !state.isExpanded,
            onFocusGained = { state.requestOpen() },
            modifier = Modifier.align(Alignment.TopStart),
        )
        SidebarPanel(
            selected = selected,
            onSelect = onSelect,
            expandProgress = expandProgress,
            canTakeFocus = state.isExpanded,
            itemRequesters = itemRequesters,
            onFocusGained = { state.onPanelFocusGained() },
            onFocusLost = { state.requestDismiss() },
            onNavigate = { state.requestDismiss(transferFocusToContent = true) },
            modifier = Modifier.align(Alignment.TopStart),
        )
    }
}

@Composable
private fun SidebarRail(
    selected: TvDestination,
    onSelect: (TvDestination) -> Unit,
    expandProgress: Float,
    canTakeFocus: Boolean,
    onFocusGained: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(SidebarCollapsedWidth)
            .fillMaxHeight()
            .alpha(1f - expandProgress)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.45f))
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        TvDestination.entries.forEach { destination ->
            SidebarItem(
                icon = destination.icon,
                label = destination.label,
                selected = destination == selected,
                showLabel = false,
                canTakeFocus = canTakeFocus,
                onFocusGained = onFocusGained,
                onClick = { onSelect(destination) },
                itemRequester = null,
            )
        }
    }
}

@Composable
private fun SidebarPanel(
    selected: TvDestination,
    onSelect: (TvDestination) -> Unit,
    expandProgress: Float,
    canTakeFocus: Boolean,
    itemRequesters: Map<TvDestination, FocusRequester>,
    onFocusGained: () -> Unit,
    onFocusLost: () -> Unit,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(SidebarExpandedWidth)
            .fillMaxHeight()
            .graphicsLayer {
                alpha = expandProgress
                val scale = 0.92f + 0.08f * expandProgress
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            .padding(vertical = 24.dp)
            .onFocusChanged { focusState ->
                if (focusState.hasFocus) {
                    onFocusGained()
                } else {
                    onFocusLost()
                }
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        TvDestination.entries.forEach { destination ->
            SidebarItem(
                icon = destination.icon,
                label = destination.label,
                selected = destination == selected,
                showLabel = true,
                canTakeFocus = canTakeFocus,
                onFocusGained = onFocusGained,
                onClick = {
                    onSelect(destination)
                    onNavigate()
                },
                itemRequester = itemRequesters[destination],
            )
        }
    }
}

@Composable
private fun SidebarItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    showLabel: Boolean,
    canTakeFocus: Boolean,
    onFocusGained: () -> Unit,
    onClick: () -> Unit,
    itemRequester: FocusRequester?,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (showLabel) Arrangement.Start else Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (showLabel) 20.dp else 0.dp)
            .graphicsLayer {
                scaleX = if (focused) 1.04f else 1f
                scaleY = if (focused) 1.04f else 1f
            }
            .onFocusChanged { focusState ->
                focused = focusState.isFocused
                if (focusState.isFocused) {
                    onFocusGained()
                }
            }
            .focusProperties { canFocus = canTakeFocus }
            .then(if (itemRequester != null) Modifier.focusRequester(itemRequester) else Modifier)
            .clip(SidebarItemShape)
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
                        SidebarItemShape,
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
        if (showLabel) {
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

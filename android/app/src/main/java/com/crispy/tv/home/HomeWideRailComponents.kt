package com.crispy.tv.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import coil3.compose.AsyncImage
import com.crispy.tv.player.CanonicalContinueWatchingItem
import com.crispy.tv.ui.components.ItemActionSheet
import com.crispy.tv.ui.components.ItemActionSheetItem
import com.crispy.tv.ui.components.rememberCrispyImageModel
import com.crispy.tv.ui.components.skeletonElement
import com.crispy.tv.ui.edge_to_edge.crispyRowHuggingPadding
import com.crispy.tv.ui.navigation.LocalNavAnimatedContentScope
import com.crispy.tv.ui.navigation.LocalSharedTransitionScope
import com.crispy.tv.ui.navigation.animateCardCornerRadius
import com.crispy.tv.ui.navigation.animateCardOverlayAlpha
import com.crispy.tv.ui.theme.Dimensions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeWideRailSection(
    section: HomeWideRailSectionUi,
    horizontalPadding: Dp,
    onContinueWatchingClick: (CanonicalContinueWatchingItem, String?) -> Unit,
    onContinueWatchingOpenDetails: (CanonicalContinueWatchingItem, String?) -> Unit,
    onRemoveContinueWatchingItem: (CanonicalContinueWatchingItem) -> Unit,
    onThisWeekClick: (CalendarEpisodeItem, String?) -> Unit,
    onViewAllClick: (() -> Unit)? = null,
) {
    val isLoading = section.state is RailLoadState.Loading
    val readyItems = (section.state as? RailLoadState.Ready)?.items.orEmpty()

    var actionsItemKey by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HomeRailHeader(
            title = section.title,
            skeleton = isLoading,
            action = onViewAllClick?.let { action ->
                {
                    TextButton(onClick = action) {
                        Text("View all")
                    }
                }
            },
            modifier = Modifier.padding(horizontal = horizontalPadding),
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = crispyRowHuggingPadding(horizontalPadding),
        ) {
            if (isLoading) {
                items(HOME_WIDE_SKELETON_COUNT, contentType = { "wideSkeleton" }) {
                    HomeWideRailSkeletonCard()
                }
            } else {
                items(readyItems, key = { it.key }, contentType = { "wideRailCard" }) { item ->
                    val key = "homerail-${section.kind.name.lowercase()}-${item.key}"
                    HomeWideRailCard(
                        item = item,
                        showActions = section.kind == HomeWideRailSectionKind.CONTINUE_WATCHING,
                        actionsVisible = actionsItemKey == item.key,
                        onToggleActions = { key2 ->
                            actionsItemKey = if (actionsItemKey == key2) null else key2
                        },
                        onClick = {
                            when (item.kind) {
                                HomeWideRailItemKind.WATCH_ACTIVITY -> item.continueWatchingItem?.let { onContinueWatchingClick(it, key) }
                                HomeWideRailItemKind.CALENDAR_EPISODE -> item.calendarEpisodeItem?.let { onThisWeekClick(it, key) }
                            }
                        },
                        onDetailsClick = {
                            when (item.kind) {
                                HomeWideRailItemKind.WATCH_ACTIVITY -> item.continueWatchingItem?.let { onContinueWatchingOpenDetails(it, key) }
                                HomeWideRailItemKind.CALENDAR_EPISODE -> item.calendarEpisodeItem?.let { onThisWeekClick(it, key) }
                            }
                        },
                        sharedElementKey = key,
                        onRemoveClick =
                            if (section.kind == HomeWideRailSectionKind.CONTINUE_WATCHING) {
                                item.continueWatchingItem?.let { continueWatchingItem ->
                                    { onRemoveContinueWatchingItem(continueWatchingItem) }
                                }
                            } else {
                                null
                            },
                    )
                }
            }
        }
    }

    actionsItemKey?.let { key ->
        val actionItem = readyItems.firstOrNull { it.key == key }
        if (actionItem != null) {
            val actionSharedKey = "homerail-${section.kind.name.lowercase()}-${actionItem.key}"
            val actions = buildList {
                add(
                    ItemActionSheetItem(
                        label = "Open details",
                        icon = Icons.AutoMirrored.Filled.OpenInNew,
                        onClick = {
                            actionsItemKey = null
                            when (actionItem.kind) {
                                HomeWideRailItemKind.WATCH_ACTIVITY -> actionItem.continueWatchingItem?.let { onContinueWatchingOpenDetails(it, actionSharedKey) }
                                HomeWideRailItemKind.CALENDAR_EPISODE -> actionItem.calendarEpisodeItem?.let { onThisWeekClick(it, actionSharedKey) }
                            }
                        },
                    ),
                )
                actionItem.continueWatchingItem?.let { cwItem ->
                    add(
                        ItemActionSheetItem(
                            label = "Remove",
                            icon = Icons.Outlined.Delete,
                            destructive = true,
                            dividerBefore = true,
                            onClick = {
                                actionsItemKey = null
                                onRemoveContinueWatchingItem(cwItem)
                            },
                        ),
                    )
                }
            }
            ModalBottomSheet(
                onDismissRequest = { actionsItemKey = null },
                sheetState = sheetState,
            ) {
                ItemActionSheet(
                    title = actionItem.title,
                    subtitle = listOfNotNull(
                        actionItem.subtitle.takeIf { it.isNotBlank() },
                        actionItem.continueWatchingItem?.genre?.takeIf { it.isNotBlank() },
                    ).joinToString(" · ").takeIf { it.isNotBlank() },
                    imageUrl = actionItem.imageUrl,
                    actions = actions,
                )
            }
        }
    }
}

private const val HOME_WIDE_SKELETON_COUNT = 3

@Composable
private fun HomeWideRailSkeletonCard() {
    Box(
        modifier = Modifier
            .width(Dimensions.WideCardWidth)
            .aspectRatio(Dimensions.WideCardAspectRatio)
            .skeletonElement(shape = RoundedCornerShape(16.dp), pulse = false)
    )
}

@Composable
internal fun HomeRailHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    skeleton: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (skeleton) {
                Box(modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(Dimensions.SectionTitleSkeletonWidthFraction)
                            .height(Dimensions.SectionTitleSkeletonHeight)
                            .skeletonElement(shape = RoundedCornerShape(4.dp), pulse = false)
                    )
                }
            } else {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            action?.invoke()
        }

        if (!skeleton) {
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun HomeWideRailCard(
    item: HomeWideRailItemUi,
    showActions: Boolean,
    actionsVisible: Boolean,
    onToggleActions: (String) -> Unit,
    onClick: () -> Unit,
    onDetailsClick: () -> Unit = onClick,
    onRemoveClick: (() -> Unit)? = null,
    sharedElementKey: String? = null,
) {
    val removeAction = onRemoveClick
    val hasItemActions = showActions && removeAction != null
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalNavAnimatedContentScope.current
    val resolvedKey = sharedElementKey?.takeIf { it.isNotBlank() } ?: item.detailsItemId
    val backdropKey = resolvedKey?.let { "backdrop-$it" }
    val logoKey = resolvedKey?.let { "logo-$it" }
    val artworkModel = rememberCrispyImageModel(
        url = item.imageUrl,
        width = Dimensions.WideCardWidth,
        height = Dimensions.WideCardWidth / Dimensions.WideCardAspectRatio,
        memoryCacheKey = backdropKey,
    )

    val backdropModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null && backdropKey != null) {
        val screenBackground = MaterialTheme.colorScheme.background
        val bottomFadeBrush = remember(screenBackground) {
            Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    0.66f to Color.Transparent,
                    1f to screenBackground,
                ),
            )
        }
        val cornerRadius = with(animatedVisibilityScope) {
            animateCardCornerRadius(20.dp)
        }
        val overlayAlpha = with(animatedVisibilityScope) {
            animateCardOverlayAlpha()
        }
        with(sharedTransitionScope) {
            Modifier
                .sharedElement(
                    rememberSharedContentState(key = backdropKey),
                    animatedVisibilityScope = animatedVisibilityScope,
                )
                .clip(RoundedCornerShape(cornerRadius))
                .drawWithContent {
                    drawContent()
                    if (overlayAlpha > 0.001f) {
                        drawRect(brush = bottomFadeBrush, alpha = overlayAlpha)
                    }
                }
        }
    } else {
        Modifier
    }

    val cardInteractionModifier =
        if (hasItemActions) {
            Modifier.combinedClickable(
                onClick = onClick,
                onLongClickLabel = "Item actions",
                onLongClick = { onToggleActions(item.key) },
            )
        } else {
            Modifier.clickable(onClick = onClick)
        }

    LandscapeArtworkFrame(
        title = item.title,
        imageModel = artworkModel,
        onClick = null,
        modifier = Modifier
            .width(Dimensions.WideCardWidth)
            .aspectRatio(Dimensions.WideCardAspectRatio)
            .then(cardInteractionModifier),
        badgeLabel = item.badgeLabel,
        badgeAlignment = Alignment.TopEnd,
        progressFraction = item.progressFraction,
        scrimHeightFraction = 0.55f,
        scrimMaxAlpha = 0.88f,
        imageModifier = backdropModifier,
        bottomOverlayContent = {
            val logoModel = rememberCrispyImageModel(
                url = item.logoUrl,
                width = 112.dp,
                height = 30.dp,
                memoryCacheKey = logoKey,
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (logoModel != null) {
                    val logoModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null && logoKey != null) {
                        with(sharedTransitionScope) {
                            Modifier
                                .sharedElement(
                                    rememberSharedContentState(key = logoKey),
                                    animatedVisibilityScope = animatedVisibilityScope,
                                )
                                .fillMaxWidth(0.60f)
                                .height(30.dp)
                        }
                    } else {
                        Modifier
                            .fillMaxWidth(0.60f)
                            .height(30.dp)
                    }
                    AsyncImage(
                        model = logoModel,
                        contentDescription = item.title,
                        modifier = logoModifier,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart,
                    )
                } else {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (item.subtitle.isNotBlank()) {
                    Text(
                        text = item.subtitle,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
    )
}

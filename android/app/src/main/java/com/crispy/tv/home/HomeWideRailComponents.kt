package com.crispy.tv.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crispy.tv.ui.components.skeletonElement

private const val HOME_WIDE_SKELETON_COUNT = 3

@Composable
internal fun HomeWideRailSection(
    section: HomeWideRailSectionUi,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onHideContinueWatchingItem: (ContinueWatchingItem) -> Unit,
    onRemoveContinueWatchingItem: (ContinueWatchingItem) -> Unit,
    onThisWeekClick: (CalendarEpisodeItem) -> Unit,
    onViewAllClick: (() -> Unit)? = null,
) {
    if (section.items.isEmpty() && !section.isLoading && section.statusMessage.isBlank()) {
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HomeRailHeader(
            title = section.title,
            statusMessage = if (section.isLoading) "" else section.statusMessage,
            action = onViewAllClick?.let { action ->
                {
                    TextButton(onClick = action) {
                        Text("View all")
                    }
                }
            },
        )

        if (section.isLoading || section.items.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                if (section.isLoading && section.items.isEmpty()) {
                    items(HOME_WIDE_SKELETON_COUNT) {
                        HomeWideRailSkeletonCard()
                    }
                } else {
                    items(section.items, key = { it.key }) { item ->
                        HomeWideRailCard(
                            item = item,
                            showActions = section.kind == HomeWideRailSectionKind.CONTINUE_WATCHING,
                            onClick = {
                                when (item.kind) {
                                    HomeWideRailItemKind.WATCH_ACTIVITY -> item.continueWatchingItem?.let(onContinueWatchingClick)
                                    HomeWideRailItemKind.CALENDAR_EPISODE -> item.calendarEpisodeItem?.let(onThisWeekClick)
                                }
                            },
                            onDetailsClick = {
                                when (item.kind) {
                                    HomeWideRailItemKind.WATCH_ACTIVITY -> item.continueWatchingItem?.let(onContinueWatchingClick)
                                    HomeWideRailItemKind.CALENDAR_EPISODE -> item.calendarEpisodeItem?.let(onThisWeekClick)
                                }
                            },
                            onHideClick =
                                if (section.kind == HomeWideRailSectionKind.CONTINUE_WATCHING) {
                                    item.continueWatchingItem?.let { continueWatchingItem ->
                                        { onHideContinueWatchingItem(continueWatchingItem) }
                                    }
                                } else {
                                    null
                                },
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
    }

}

@Composable
private fun HomeWideRailSkeletonCard() {
    Column(
        modifier = Modifier.width(280.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .skeletonElement(shape = RoundedCornerShape(16.dp), pulse = false)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(16.dp)
                .skeletonElement(pulse = false)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.45f)
                .height(12.dp)
                .skeletonElement(pulse = false)
        )
    }
}

@Composable
internal fun HomeRailHeader(
    title: String,
    statusMessage: String,
    action: (@Composable () -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            action?.invoke()
        }

        if (statusMessage.isNotBlank()) {
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

}

@Composable
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
internal fun HomeWideRailCard(
    item: HomeWideRailItemUi,
    showActions: Boolean,
    onClick: () -> Unit,
    onDetailsClick: () -> Unit = onClick,
    onHideClick: (() -> Unit)? = null,
    onRemoveClick: (() -> Unit)? = null,
) {
    var actionSheetVisible by remember { mutableStateOf(false) }
    val hasItemActions = showActions && (onHideClick != null || onRemoveClick != null)
    val artworkModel = rememberLandscapeImageModel(item.imageUrl, 280.dp)

    val cardInteractionModifier =
        if (hasItemActions) {
            Modifier.combinedClickable(
                onClick = onClick,
                onLongClickLabel = "Item actions",
                onLongClick = { actionSheetVisible = true },
            )
        } else {
            Modifier.clickable(onClick = onClick)
        }

    Column(
        modifier = Modifier
            .width(280.dp)
            .then(cardInteractionModifier),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LandscapeArtworkFrame(
            title = item.title,
            imageModel = artworkModel,
            onClick = null,
            modifier = Modifier.aspectRatio(16f / 9f),
            badgeLabel = item.badgeLabel,
            badgeAlignment = Alignment.TopEnd,
            progressFraction = item.progressFraction,
            scrimHeightFraction = if (item.progressFraction != null) 0.42f else 0f,
            scrimMaxAlpha = if (item.progressFraction != null) 0.36f else 0f,
        )

        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )

        if (item.subtitle.isNotBlank()) {
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (hasItemActions && actionSheetVisible) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { actionSheetVisible = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                ListItem(
                    headlineContent = { Text("Details") },
                    supportingContent = {
                        if (item.subtitle.isNotBlank()) {
                            Text(
                                text = item.subtitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    modifier = Modifier.clickable {
                        actionSheetVisible = false
                        onDetailsClick()
                    },
                )
                onRemoveClick?.let { removeAction ->
                    ListItem(
                        headlineContent = { Text("Remove") },
                        modifier = Modifier.clickable {
                            actionSheetVisible = false
                            removeAction()
                        },
                    )
                }
                onHideClick?.let { hideAction ->
                    ListItem(
                        headlineContent = { Text("Hide") },
                        modifier = Modifier.clickable {
                            actionSheetVisible = false
                            hideAction()
                        },
                    )
                }
            }
        }
    }

}

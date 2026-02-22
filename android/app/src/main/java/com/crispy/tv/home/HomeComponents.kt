package com.crispy.tv.home

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.crispy.tv.catalog.CatalogItem
import java.util.Locale

@Composable
internal fun HomeRailSection(
    title: String,
    items: List<ContinueWatchingItem>,
    statusMessage: String,
    actionMenuContentDescription: String,
    subtitleFor: (ContinueWatchingItem) -> String,
    onItemClick: (ContinueWatchingItem) -> Unit,
    onHideItem: ((ContinueWatchingItem) -> Unit)? = null,
    onRemoveItem: ((ContinueWatchingItem) -> Unit)? = null,
    badgeLabel: String? = null,
    showProgressBar: Boolean = false,
    showTitleFallbackWhenNoLogo: Boolean = false,
    useBottomSheetActions: Boolean = false
) {
    if (items.isEmpty()) {
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HomeRailHeader(
            title = title,
            statusMessage = statusMessage
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(0.dp)
        ) {
            items(items, key = { it.id }) { item ->
                HomeRailCard(
                    item = item,
                    subtitle = subtitleFor(item),
                    actionMenuContentDescription = actionMenuContentDescription,
                    onClick = { onItemClick(item) },
                    onHideClick = onHideItem?.let { { it(item) } },
                    onRemoveClick = onRemoveItem?.let { { it(item) } },
                    onDetailsClick = { onItemClick(item) },
                    badgeLabel = badgeLabel,
                    showProgressBar = showProgressBar,
                    showTitleFallbackWhenNoLogo = showTitleFallbackWhenNoLogo,
                    useBottomSheetActions = useBottomSheetActions
                )
            }
        }
    }
}

@Composable
internal fun HomeRailHeader(
    title: String,
    statusMessage: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )

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
internal fun HomeRailCard(
    item: ContinueWatchingItem,
    subtitle: String,
    actionMenuContentDescription: String,
    onClick: () -> Unit,
    onHideClick: (() -> Unit)? = null,
    onRemoveClick: (() -> Unit)? = null,
    onDetailsClick: () -> Unit,
    badgeLabel: String? = null,
    showProgressBar: Boolean = false,
    showTitleFallbackWhenNoLogo: Boolean = false,
    useBottomSheetActions: Boolean = false
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var bottomSheetVisible by remember { mutableStateOf(false) }
    val hasItemActions = onHideClick != null || onRemoveClick != null

    val cardInteractionModifier = if (useBottomSheetActions && hasItemActions) {
        Modifier.combinedClickable(
            onClick = onClick,
            onLongClickLabel = actionMenuContentDescription,
            onLongClick = { bottomSheetVisible = true }
        )
    } else {
        Modifier.clickable(onClick = onClick)
    }

    Box(
        modifier = Modifier
            .width(260.dp)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(28.dp))
            .then(cardInteractionModifier)
    ) {
        if (!item.backdropUrl.isNullOrBlank()) {
            AsyncImage(
                model = item.backdropUrl,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.64f)
                        )
                    )
                )
        )

        if (!badgeLabel.isNullOrBlank()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = 10.dp),
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.primary
            ) {
                Text(
                    text = badgeLabel,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }

        if (!item.logoUrl.isNullOrBlank()) {
            AsyncImage(
                model = item.logoUrl,
                contentDescription = "${item.title} logo",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(0.6f)
                    .height(56.dp)
                    .padding(top = 12.dp),
                contentScale = ContentScale.Fit
            )
        } else if (showTitleFallbackWhenNoLogo) {
            Text(
                text = item.title,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (!useBottomSheetActions && hasItemActions) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), shape = MaterialTheme.shapes.small)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = actionMenuContentDescription,
                        tint = Color.White
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Details") },
                        onClick = {
                            menuExpanded = false
                            onDetailsClick()
                        }
                    )
                    onRemoveClick?.let { removeAction ->
                        DropdownMenuItem(
                            text = { Text("Remove") },
                            onClick = {
                                menuExpanded = false
                                removeAction()
                            }
                        )
                    }
                    onHideClick?.let { hideAction ->
                        DropdownMenuItem(
                            text = { Text("Hide") },
                            onClick = {
                                menuExpanded = false
                                hideAction()
                            }
                        )
                    }
                }
            }
        }

        Text(
            text = subtitle,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.95f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (showProgressBar && item.progressPercent > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.White.copy(alpha = 0.3f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(
                            fraction = (item.progressPercent / 100.0).coerceIn(0.0, 1.0).toFloat()
                        )
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }

    if (useBottomSheetActions && hasItemActions && bottomSheetVisible) {
        HomeRailActionBottomSheet(
            title = item.title,
            subtitle = subtitle,
            onDismiss = { bottomSheetVisible = false },
            onDetailsClick = {
                bottomSheetVisible = false
                onDetailsClick()
            },
            onRemoveClick = onRemoveClick?.let {
                {
                    bottomSheetVisible = false
                    it()
                }
            },
            onHideClick = onHideClick?.let {
                {
                    bottomSheetVisible = false
                    it()
                }
            }
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun HomeRailActionBottomSheet(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    onDetailsClick: () -> Unit,
    onRemoveClick: (() -> Unit)? = null,
    onHideClick: (() -> Unit)? = null
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle) }
        )
        HorizontalDivider()
        HomeRailActionBottomSheetItem(
            label = "Details",
            icon = Icons.Outlined.Info,
            onClick = onDetailsClick
        )
        onRemoveClick?.let {
            HomeRailActionBottomSheetItem(
                label = "Remove",
                icon = Icons.Outlined.DeleteOutline,
                onClick = it
            )
        }
        onHideClick?.let {
            HomeRailActionBottomSheetItem(
                label = "Hide",
                icon = Icons.Outlined.VisibilityOff,
                onClick = it
            )
        }
    }
}

@Composable
private fun HomeRailActionBottomSheetItem(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null
            )
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

internal fun upNextSubtitle(item: ContinueWatchingItem): String {
    val seasonEpisode =
        if (
            item.type.equals("series", ignoreCase = true) &&
                item.season != null &&
                item.episode != null
        ) {
            String.format(Locale.US, "S%02d:E%02d", item.season, item.episode)
        } else {
            null
        }
    val relativeWatched = DateUtils.getRelativeTimeSpanString(
        item.watchedAtEpochMs,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS
    ).toString()

    return listOfNotNull(seasonEpisode, relativeWatched).joinToString(separator = " • ")
}

@Composable
internal fun HomeCatalogSectionRow(
    sectionUi: HomeCatalogSectionUi,
    onSeeAllClick: () -> Unit,
    onItemClick: (CatalogItem) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = sectionUi.section.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            FilledIconButton(
                onClick = onSeeAllClick,
                modifier = Modifier
                    .width(32.dp)
                    .height(48.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "See all",
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        if (sectionUi.statusMessage.isNotBlank() && sectionUi.items.isEmpty()) {
            Text(
                text = sectionUi.statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (sectionUi.isLoading && sectionUi.items.isEmpty()) {
                items(10) {
                    Card(
                        modifier = Modifier
                            .width(124.dp)
                            .aspectRatio(2f / 3f)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                    }
                }
            } else {
                items(sectionUi.items, key = { it.id }) { item ->
                    HomeCatalogPosterCard(
                        item = item,
                        onClick = { onItemClick(item) }
                    )
                }
            }
        }
    }
}

@Composable
internal fun HomeCatalogPosterCard(
    item: CatalogItem,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier.width(124.dp)
    ) {
        Card(
            modifier = Modifier
                .aspectRatio(2f / 3f)
                .clip(MaterialTheme.shapes.large)
                .clickable(onClick = onClick)
        ) {
            val imageUrl = item.posterUrl ?: item.backdropUrl
            Box(modifier = Modifier.fillMaxSize()) {
                if (!imageUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                }

                if (!item.rating.isNullOrBlank()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = Color.Black.copy(alpha = 0.7f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Star,
                                contentDescription = null,
                                modifier = Modifier.height(12.dp),
                                tint = Color(0xFFFFC107)
                            )
                            Text(
                                text = item.rating,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .height(40.dp)
        )
    }
}

internal fun continueWatchingSubtitle(item: ContinueWatchingItem): String {
    val upNext = if (item.isUpNextPlaceholder) "Up Next" else null
    val seasonEpisode =
        if (
            item.type.equals("series", ignoreCase = true) &&
                item.season != null &&
                item.episode != null
        ) {
            String.format(Locale.US, "S%02d:E%02d", item.season, item.episode)
        } else {
            null
        }
    val relativeWatched = DateUtils.getRelativeTimeSpanString(
        item.watchedAtEpochMs,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS
    ).toString()

    return listOfNotNull(upNext, seasonEpisode, relativeWatched).joinToString(separator = " • ")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HomeHeroCarousel(
    items: List<HomeHeroItem>,
    selectedId: String?,
    onItemClick: (HomeHeroItem) -> Unit
) {
    if (items.isEmpty()) {
        return
    }

    val initialIndex = remember(selectedId, items) {
        selectedId?.let { id ->
            items.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: 0
        } ?: 0
    }
    val state = rememberCarouselState(initialItem = initialIndex) { items.size }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(340.dp),
        contentAlignment = Alignment.Center
    ) {
        HorizontalMultiBrowseCarousel(
            state = state,
            preferredItemWidth = 320.dp,
            itemSpacing = 16.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
        ) { index ->
            val item = items[index]

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .maskClip(RoundedCornerShape(28.dp))
                    .clickable { onItemClick(item) }
            ) {
                AsyncImage(
                    model = item.backdropUrl,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.72f)
                                )
                            )
                        )
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    val subtitle = listOfNotNull(
                        item.year,
                        item.genres.firstOrNull()
                    ).joinToString(" • ")

                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

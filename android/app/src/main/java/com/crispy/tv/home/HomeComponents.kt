package com.crispy.tv.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.maskClip
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.ui.components.rememberCrispyImageModel

import com.crispy.tv.ui.components.skeletonElement

@Composable
internal fun HomeRailSection(
    title: String,
    items: List<HomeWatchActivityItemUi>,
    statusMessage: String,
    actionMenuContentDescription: String,
    onItemClick: (ContinueWatchingItem) -> Unit,
    onHideItem: ((ContinueWatchingItem) -> Unit)? = null,
    onRemoveItem: ((ContinueWatchingItem) -> Unit)? = null,
    badgeLabelFor: (ContinueWatchingItem) -> String? = { null },
    showProgressBarFor: (ContinueWatchingItem) -> Boolean = { false },
    showTitleFallbackWhenNoLogo: Boolean = false,
    useBottomSheetActions: Boolean = false,
    usePosterCardStyle: Boolean = false,
    isLoading: Boolean = false
) {
    if (items.isEmpty() && !isLoading && statusMessage.isBlank()) {
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HomeRailHeader(
            title = title,
            statusMessage = if (isLoading) "" else statusMessage
        )

        if (isLoading || items.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                if (isLoading && items.isEmpty()) {
                    if (usePosterCardStyle) {
                        items(HOME_POSTER_SKELETON_COUNT) {
                            HomeRailPosterSkeletonCard()
                        }
                    } else {
                        items(HOME_WIDE_SKELETON_COUNT) {
                            HomeRailSkeletonCard()
                        }
                    }
                } else {
                    items(items, key = { "${it.item.type}:${it.item.id}" }) { railItem ->
                        val item = railItem.item
                        if (usePosterCardStyle) {
                            HomeRailPosterCard(
                                item = item,
                                onClick = { onItemClick(item) }
                            )
                        } else {
                            HomeRailCard(
                                item = item,
                                subtitle = railItem.subtitle,
                                actionMenuContentDescription = actionMenuContentDescription,
                                onClick = { onItemClick(item) },
                                onHideClick = onHideItem?.let { { it(item) } },
                                onRemoveClick = onRemoveItem?.let { { it(item) } },
                                onDetailsClick = { onItemClick(item) },
                                badgeLabel = badgeLabelFor(item),
                                showProgressBar = showProgressBarFor(item),
                                showTitleFallbackWhenNoLogo = showTitleFallbackWhenNoLogo,
                                useBottomSheetActions = useBottomSheetActions
                            )
                        }
                    }
                }
            }
        }
    }

}

@Composable
private fun HomeRailSkeletonCard() {
    Column(
        modifier = Modifier.width(260.dp),
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
private fun HomeRailPosterSkeletonCard() {
    Box(
        modifier = Modifier
            .width(124.dp)
            .aspectRatio(2f / 3f)
            .skeletonElement(shape = RoundedCornerShape(8.dp), pulse = false)
    )
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
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
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
private fun LandscapeArtworkFrame(
    title: String,
    imageModel: Any?,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    badgeLabel: String? = null,
    badgeAlignment: Alignment = Alignment.TopStart,
    progressFraction: Float? = null,
    scrimHeightFraction: Float = 0.52f,
    scrimMaxAlpha: Float = 0.82f,
    topEndContent: (@Composable BoxScope.() -> Unit)? = null,
    bottomOverlayContent: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(20.dp))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        if (imageModel != null) {
            AsyncImage(
                model = imageModel,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }

        HomeArtworkBottomScrim(
            heightFraction = scrimHeightFraction,
            maxAlpha = scrimMaxAlpha,
        )

        if (!badgeLabel.isNullOrBlank()) {
            Surface(
                modifier = Modifier
                    .align(badgeAlignment)
                    .padding(start = 12.dp, top = 12.dp, end = 12.dp),
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Text(
                    text = badgeLabel,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        topEndContent?.invoke(this)
        bottomOverlayContent()

        if (progressFraction != null && progressFraction > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color.White.copy(alpha = 0.25f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progressFraction.coerceIn(0f, 1f))
                        .background(MaterialTheme.colorScheme.tertiary),
                )
            }
        }
    }
}

@Composable
private fun BoxScope.HomeArtworkBottomScrim(
    heightFraction: Float,
    maxAlpha: Float,
) {
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .fillMaxHeight(heightFraction.coerceIn(0f, 1f))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = maxAlpha),
                    ),
                ),
            ),
    )
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
    val artworkModel = rememberLandscapeImageModel(item.backdropUrl ?: item.posterUrl, 260.dp)
    val logoModel = rememberCrispyImageModel(item.logoUrl, width = 188.dp, height = 54.dp)

    val cardInteractionModifier = if (useBottomSheetActions && hasItemActions) {
        Modifier.combinedClickable(
            onClick = onClick,
            onLongClickLabel = actionMenuContentDescription,
            onLongClick = { bottomSheetVisible = true }
        )
    } else {
        Modifier.clickable(onClick = onClick)
    }

    Column(modifier = Modifier.width(260.dp)) {
        LandscapeArtworkFrame(
            title = item.title,
            imageModel = artworkModel,
            onClick = null,
            modifier = Modifier
                .aspectRatio(16f / 9f)
                .then(cardInteractionModifier),
            badgeLabel = badgeLabel,
            progressFraction =
                if (showProgressBar && item.progressPercent > 0) {
                    (item.progressPercent / 100.0).coerceIn(0.0, 1.0).toFloat()
                } else {
                    null
                },
            topEndContent =
                if (!useBottomSheetActions && hasItemActions) {
                    {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp),
                        ) {
                            IconButton(
                                onClick = { menuExpanded = true },
                                modifier = Modifier.background(
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
                                    shape = CircleShape,
                                ),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.MoreVert,
                                    contentDescription = actionMenuContentDescription,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }

                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Details") },
                                    onClick = {
                                        menuExpanded = false
                                        onDetailsClick()
                                    },
                                )
                                onRemoveClick?.let { removeAction ->
                                    DropdownMenuItem(
                                        text = { Text("Remove") },
                                        onClick = {
                                            menuExpanded = false
                                            removeAction()
                                        },
                                    )
                                }
                                onHideClick?.let { hideAction ->
                                    DropdownMenuItem(
                                        text = { Text("Hide") },
                                        onClick = {
                                            menuExpanded = false
                                            hideAction()
                                        },
                                    )
                                }
                            }
                        }
                    }
                } else {
                    null
            },
            bottomOverlayContent = {
                if (logoModel != null) {
                    AsyncImage(
                        model = logoModel,
                        contentDescription = "${item.title} logo",
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(0.72f)
                            .height(54.dp)
                            .padding(bottom = 12.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else if (showTitleFallbackWhenNoLogo) {
                    Text(
                        text = item.title,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
        )

        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
        )

        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
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
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = sectionUi.section.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (sectionUi.section.subtitle.isNotBlank()) {
                    Text(
                        text = sectionUi.section.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
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

        if (!sectionUi.isLoading && sectionUi.statusMessage.isNotBlank() && sectionUi.items.isEmpty()) {
            Text(
                text = sectionUi.statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(0.dp)
        ) {
            if (sectionUi.isLoading && sectionUi.items.isEmpty()) {
                items(HOME_POSTER_SKELETON_COUNT) {
                    Column(modifier = Modifier.width(124.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(2f / 3f)
                                .skeletonElement(pulse = false)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(14.dp)
                                .skeletonElement(pulse = false)
                        )
                    }
                }
            } else {
                items(sectionUi.items, key = { "${it.type}:${it.id}" }) { item ->
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
internal fun HomeCollectionSectionRow(
    sectionUis: List<HomeCatalogSectionUi>,
    onCollectionClick: (CatalogSectionRef) -> Unit,
) {
    val visibleSections =
        remember(sectionUis) {
            sectionUis.filter {
                it.isLoading || it.items.isNotEmpty() || it.statusMessage.isNotBlank()
            }
        }

    if (visibleSections.isEmpty()) {
        return
    }

    val sharedSubtitle =
        remember(visibleSections) {
            visibleSections
                .map { it.section.subtitle.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .singleOrNull()
                .orEmpty()
        }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HomeRailHeader(
            title = "Collections",
            statusMessage = sharedSubtitle,
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(0.dp)
        ) {
            items(visibleSections, key = { it.section.key }) { sectionUi ->
                HomeCollectionCard(
                    sectionUi = sectionUi,
                    showSubtitle = sharedSubtitle.isBlank(),
                    onCollectionClick = { onCollectionClick(sectionUi.section) },
                )
            }
        }
    }
}

@Composable
private fun HomeCollectionCard(
    sectionUi: HomeCatalogSectionUi,
    showSubtitle: Boolean,
    onCollectionClick: () -> Unit,
) {
    val artworkUrl =
        remember(sectionUi.items) {
            sectionUi.items.firstNotNullOfOrNull { item ->
                item.backdropUrl?.takeIf { it.isNotBlank() } ?: item.posterUrl?.takeIf { it.isNotBlank() }
            }
        }
    val artworkModel = rememberCrispyImageModel(artworkUrl, width = 236.dp, height = 295.dp, tmdbSize = "w500")
    val movieCountLabel = collectionMovieCountLabel(sectionUi.items.size) ?: "0 movies"

    Card(modifier = Modifier.width(236.dp).clickable(onClick = onCollectionClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 5f)
        ) {
            if (artworkModel != null) {
                AsyncImage(
                    model = artworkModel,
                    contentDescription = sectionUi.section.title,
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
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.48f)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.16f),
                                Color.Black.copy(alpha = 0.82f),
                            )
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (showSubtitle && sectionUi.section.subtitle.isNotBlank()) {
                    Text(
                        text = sectionUi.section.subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.82f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Text(
                    text = sectionUi.section.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = if (sectionUi.isLoading && sectionUi.items.isEmpty()) {
                        "Loading collection"
                    } else {
                        movieCountLabel
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.82f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun collectionMovieCountLabel(itemCount: Int): String? {
    return when {
        itemCount <= 0 -> null
        itemCount == 1 -> "1 movie"
        else -> "$itemCount movies"
    }
}

private const val HOME_WIDE_SKELETON_COUNT = 3
private const val HOME_POSTER_SKELETON_COUNT = 5

@Composable
private fun HomeRailPosterCard(
    item: ContinueWatchingItem,
    onClick: () -> Unit
) {
    val imageModel = rememberPosterImageModel(item.posterUrl ?: item.backdropUrl)
    Column(modifier = Modifier.width(124.dp)) {
        Card(
            modifier = Modifier
                .aspectRatio(2f / 3f)
                .clip(MaterialTheme.shapes.large)
                .clickable(onClick = onClick)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (imageModel != null) {
                    AsyncImage(
                        model = imageModel,
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
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
    val imageModel = rememberPosterImageModel(item.posterUrl ?: item.backdropUrl)
    Column(modifier = Modifier.width(124.dp)) {
        Card(
            modifier = Modifier
                .aspectRatio(2f / 3f)
                .clip(MaterialTheme.shapes.large)
                .clickable(onClick = onClick)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (imageModel != null) {
                    AsyncImage(
                        model = imageModel,
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
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
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .height(40.dp)
        )
    }
}

@Composable
internal fun ThisWeekSection(
    items: List<CalendarEpisodeItem>,
    isLoading: Boolean = false,
    statusMessage: String = "",
    onItemClick: (CalendarEpisodeItem) -> Unit = {},
    onViewAllClick: (() -> Unit)? = null,
) {
    if (items.isEmpty() && !isLoading && statusMessage.isBlank()) return

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HomeRailHeader(
            title = "This Week",
            statusMessage = if (isLoading) "" else statusMessage,
            action = onViewAllClick?.let { action ->
                {
                    TextButton(onClick = action) {
                        Text("View all")
                    }
                }
            },
        )

        if (isLoading || items.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(0.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (isLoading && items.isEmpty()) {
                    items(HOME_WIDE_SKELETON_COUNT) {
                        HomeRailSkeletonCard()
                    }
                } else {
                    items(items, key = { "${it.type}:${it.id}" }) { item ->
                        CalendarEpisodeCard(item = item, onClick = { onItemClick(item) })
                    }
                }
            }
        }
    }
}

@Composable
internal fun CalendarEpisodeCard(
    item: CalendarEpisodeItem,
    onClick: () -> Unit,
) {
    val imageModel = rememberLandscapeImageModel(item.thumbnailUrl ?: item.backdropUrl ?: item.posterUrl, 280.dp)
    Column(
        modifier = Modifier.width(280.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LandscapeArtworkFrame(
            title = item.seriesName,
            imageModel = imageModel,
            onClick = onClick,
            modifier = Modifier.aspectRatio(16f / 9f),
            badgeLabel = calendarBadgeLabel(item),
            badgeAlignment = Alignment.TopEnd,
            scrimHeightFraction = 0.68f,
            scrimMaxAlpha = 0.92f,
            bottomOverlayContent = {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = item.seriesName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = calendarSecondaryText(item),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
        )
    }
}

@Composable
internal fun CalendarSeriesCard(
    item: CalendarSeriesItem,
    onClick: () -> Unit,
) {
    val imageModel = rememberLandscapeImageModel(item.backdropUrl ?: item.posterUrl, 280.dp)
    Column(
        modifier = Modifier.width(280.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LandscapeArtworkFrame(
            title = item.title,
            imageModel = imageModel,
            onClick = onClick,
            modifier = Modifier.aspectRatio(16f / 9f),
            badgeLabel = "No schedule",
            bottomOverlayContent = {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    item.sourceLabel?.takeIf { it.isNotBlank() }?.let { sourceLabel ->
                        Text(
                            text = sourceLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
        )
    }
}

private fun calendarBadgeLabel(item: CalendarEpisodeItem): String? {
    if (item.isReleased) return "Released"
    return try {
        val date = java.time.LocalDate.parse(item.releaseDate.take(10))
        "${date.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${date.dayOfMonth}"
    } catch (_: Exception) {
        null
    }
}

private fun calendarEpisodeLabel(item: CalendarEpisodeItem): String {
    return if (item.episodeRange != null) {
        "S${item.season} ${item.episodeRange}"
    } else {
        "S${item.season} E${item.episode}"
    }
}

private fun calendarSecondaryText(item: CalendarEpisodeItem): String {
    val supportingText = when {
        item.isGroup -> "${item.episodeCount} new episodes"
        !item.episodeTitle.isNullOrBlank() -> item.episodeTitle
        !item.overview.isNullOrBlank() -> item.overview
        else -> null
    }?.trim()

    val episodeLabel = calendarEpisodeLabel(item)
    return if (supportingText.isNullOrBlank()) {
        episodeLabel
    } else {
        "$episodeLabel - $supportingText"
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HomeHeroSkeleton() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
            .skeletonElement(shape = RoundedCornerShape(28.dp), pulse = false)
    )
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

    HorizontalMultiBrowseCarousel(
        state = state,
        preferredItemWidth = 320.dp,
        itemSpacing = 16.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
    ) { index ->
        val item = items[index]
        val heroImageModel = rememberCrispyImageModel(item.backdropUrl, width = 320.dp, height = 320.dp, tmdbSize = "w780")

        Box(
            modifier = Modifier
                .fillMaxSize()
                .maskClip(RoundedCornerShape(28.dp))
                .clickable { onItemClick(item) }
        ) {
            if (heroImageModel != null) {
                AsyncImage(
                    model = heroImageModel,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }

            HomeArtworkBottomScrim(
                heightFraction = 0.46f,
                maxAlpha = 0.72f,
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

@Composable
private fun rememberPosterImageModel(url: String?): Any? {
    return rememberCrispyImageModel(url = url, width = 124.dp, height = 186.dp, tmdbSize = "w342")
}

@Composable
private fun rememberLandscapeImageModel(url: String?, width: Dp): Any? {
    return rememberCrispyImageModel(
        url = url,
        width = width,
        height = width * (9f / 16f),
        tmdbSize = "w500",
    )
}

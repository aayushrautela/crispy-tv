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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.ratings.normalizeRatingText
import com.crispy.tv.ui.components.rememberCrispyImageModel

import com.crispy.tv.ui.components.skeletonElement

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
internal fun HomeWideRailCard(
    item: HomeWideRailItemUi,
    showActions: Boolean,
    onClick: () -> Unit,
    onDetailsClick: () -> Unit = onClick,
    onHideClick: (() -> Unit)? = null,
    onRemoveClick: (() -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val hasItemActions = showActions && (onHideClick != null || onRemoveClick != null)
    val artworkModel = rememberLandscapeImageModel(item.imageUrl, 280.dp)

    val cardInteractionModifier =
        if (hasItemActions) {
            Modifier.combinedClickable(
                onClick = onClick,
                onLongClickLabel = "Item actions",
                onLongClick = { menuExpanded = true },
            )
        } else {
            Modifier.clickable(onClick = onClick)
        }

    Column(
        modifier = Modifier.width(280.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LandscapeArtworkFrame(
            title = item.title,
            imageModel = artworkModel,
            onClick = null,
            modifier = Modifier
                .aspectRatio(16f / 9f)
                .then(cardInteractionModifier),
            badgeLabel = item.badgeLabel,
            badgeAlignment = Alignment.TopEnd,
            progressFraction = item.progressFraction,
            scrimHeightFraction = if (item.progressFraction != null) 0.42f else 0f,
            scrimMaxAlpha = if (item.progressFraction != null) 0.36f else 0f,
            topEndContent =
                if (hasItemActions) {
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
                                    contentDescription = "Item actions",
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
                    text = sectionUi.section.displayTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
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
    onCollectionPlayClick: (CatalogItem) -> Unit,
    onCollectionMovieClick: (CatalogItem) -> Unit,
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
                    onCollectionClick = { onCollectionClick(sectionUi.section) },
                    onPlayClick = { onCollectionPlayClick(it) },
                    onCollectionMovieClick = onCollectionMovieClick,
                )
            }
        }
    }
}

@Composable
private fun HomeCollectionCard(
    sectionUi: HomeCatalogSectionUi,
    onCollectionClick: () -> Unit,
    onPlayClick: (CatalogItem) -> Unit,
    onCollectionMovieClick: (CatalogItem) -> Unit,
) {
    val previewMovies = remember(sectionUi.items) { sectionUi.items.take(3) }
    val featuredMovie = remember(sectionUi.items) { sectionUi.items.firstOrNull() }
    val logoUrl = remember(featuredMovie?.logoUrl) { featuredMovie?.logoUrl?.trim()?.ifBlank { null } }
    val logoModel = rememberCrispyImageModel(
        url = logoUrl,
        width = 256.dp,
        height = 80.dp,
        enableCrossfade = true,
    )
    val collectionTitle = remember(sectionUi.section.displayTitle) { collectionDisplayTitle(sectionUi.section.displayTitle) }

    Card(
        modifier = Modifier
            .width(320.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onCollectionClick)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                if (logoModel != null) {
                    AsyncImage(
                        model = logoModel,
                        contentDescription = sectionUi.section.displayTitle,
                        modifier = Modifier
                            .fillMaxWidth(0.82f)
                            .height(72.dp)
                            .padding(vertical = 4.dp),
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.Center,
                    )
                } else {
                    Text(
                        text = collectionTitle,
                        modifier = Modifier.padding(horizontal = 20.dp),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            when {
                previewMovies.isNotEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        previewMovies.forEach { item ->
                            HomeCollectionCompactMovieRow(
                                item = item,
                                onClick = { onCollectionMovieClick(item) },
                            )
                        }
                    }
                }

                sectionUi.isLoading -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        HomeCollectionMovieSkeletonRow()
                        HomeCollectionMovieSkeletonRow()
                        HomeCollectionMovieSkeletonRow()
                    }
                }

                else -> {
                    Text(
                        text = sectionUi.statusMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledIconButton(
                    onClick = { featuredMovie?.let(onPlayClick) },
                    enabled = featuredMovie != null,
                    modifier = Modifier.size(52.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Play collection",
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                FilledIconButton(
                    onClick = onCollectionClick,
                    modifier = Modifier.size(52.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "Collection info",
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeCollectionMovieRow(
    item: CatalogItem,
    onClick: () -> Unit,
) {
    val posterUrl = item.posterUrl?.takeIf { it.isNotBlank() } ?: item.backdropUrl?.takeIf { it.isNotBlank() }
    val imageModel = rememberCrispyImageModel(posterUrl, width = 56.dp, height = 56.dp, tmdbSize = "w185")
    val detailText = collectionMovieMetaText(item)
    val ratingText = normalizeRatingText(item.rating)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (imageModel != null) {
                AsyncImage(
                    model = imageModel,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (detailText.isNotBlank() || ratingText != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (detailText.isNotBlank()) {
                        Text(
                            text = detailText,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    ratingText?.let { rating ->
                        HomeCollectionRatingBadge(
                            rating = rating,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeCollectionMovieSkeletonRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .skeletonElement(shape = RoundedCornerShape(12.dp), pulse = false)
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.72f)
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
}

private fun collectionDisplayTitle(title: String): String {
    val trimmedTitle = title.trim()
    val simplifiedTitle = trimmedTitle.replace(Regex("\\s+collection$", RegexOption.IGNORE_CASE), "")
    return simplifiedTitle.ifBlank { trimmedTitle }
}

private fun collectionMovieMetaText(item: CatalogItem): String {
    return buildList {
        item.year?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
        item.genre?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
    }.joinToString(separator = " • ")
}

private const val HOME_WIDE_SKELETON_COUNT = 3
private const val HOME_POSTER_SKELETON_COUNT = 5

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

                normalizeRatingText(item.rating)?.let { rating ->
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
                                text = rating,
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
        val heroImageModel = rememberCrispyImageModel(
            item.backdropUrl,
            width = 320.dp,
            height = 320.dp,
            tmdbSize = "w780",
            enableCrossfade = true,
        )

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
private fun HomeCollectionRatingBadge(
    rating: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = Color.Black.copy(alpha = 0.7f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Star,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = Color.White,
            )
            Text(
                text = rating,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
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

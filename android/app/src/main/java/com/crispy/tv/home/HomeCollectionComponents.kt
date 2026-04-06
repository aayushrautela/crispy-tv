package com.crispy.tv.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.ratings.normalizeRatingText
import com.crispy.tv.ui.components.rememberCrispyImageModel
import com.crispy.tv.ui.components.skeletonElement

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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
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
                            HomeCollectionMovieRow(
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

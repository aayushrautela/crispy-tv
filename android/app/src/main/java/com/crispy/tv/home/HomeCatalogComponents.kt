package com.crispy.tv.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.ui.components.CardStyle
import com.crispy.tv.ui.components.LandscapeCard
import com.crispy.tv.ui.components.skeletonElement
import com.crispy.tv.ui.edge_to_edge.crispyRowHuggingPadding
import com.crispy.tv.ui.theme.Dimensions

private const val HOME_POSTER_SKELETON_COUNT = 5

@Composable
internal fun HomeCatalogSectionRow(
    sectionUi: HomeCatalogSectionUi,
    horizontalPadding: Dp,
    onSeeAllClick: () -> Unit,
    onItemClick: (CatalogItem, String?) -> Unit,
) {
    val sectionSkeleton = sectionUi.isLoading && sectionUi.items.isEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (sectionSkeleton) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(Dimensions.SectionTitleSkeletonWidthFraction)
                            .height(Dimensions.SectionTitleSkeletonHeight)
                            .skeletonElement(shape = RoundedCornerShape(4.dp), pulse = false)
                    )
                    if (sectionUi.section.subtitle.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(Dimensions.SectionSubtitleSkeletonWidthFraction)
                                .height(Dimensions.SectionSubtitleSkeletonHeight)
                                .skeletonElement(shape = RoundedCornerShape(4.dp), pulse = false)
                        )
                    }
                } else {
                    Text(
                        text = sectionUi.section.displayTitle,
                        style = MaterialTheme.typography.titleMedium,
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

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = crispyRowHuggingPadding(horizontalPadding),
        ) {
            if (sectionUi.isLoading && sectionUi.items.isEmpty()) {
                items(HOME_POSTER_SKELETON_COUNT, contentType = { "posterSkeleton" }) {
                    Box(
                        modifier = Modifier
                            .width(CardStyle.landscapeCardWidth())
                            .aspectRatio(CardStyle.LandscapeAspectRatio)
                            .skeletonElement(pulse = false)
                    )
                }
            } else {
                items(sectionUi.items, key = { "${it.type}:${it.id}" }, contentType = { "catalogPoster" }) { item ->
                    val key = "homecatalog-${sectionUi.section.key}-${item.itemId}"
                    HomeCatalogPosterCard(
                        item = item,
                        sharedElementKey = key,
                        onClick = {
                            onItemClick(item, key)
                        }
                    )
                }
            }
        }
    }

}

@Composable
internal fun HomeCatalogPosterCard(
    item: CatalogItem,
    onClick: () -> Unit,
    sharedElementKey: String? = null,
) {
    LandscapeCard(
        title = item.title,
        backdropUrl = item.backdropUrl,
        posterUrl = item.posterUrl,
        logoUrl = item.logoUrl,
        backdrop = item.backdrop,
        poster = item.poster,
        logo = item.logo,
        rating = item.rating,
        year = item.year,
        genre = item.genre,
        modifier = Modifier.width(CardStyle.landscapeCardWidth()),
        onClick = onClick,
        itemId = item.itemId,
        sharedElementKey = sharedElementKey,
    )
}

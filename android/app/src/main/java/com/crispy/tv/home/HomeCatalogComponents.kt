package com.crispy.tv.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.ui.components.PosterCard
import com.crispy.tv.ui.components.skeletonElement
import com.crispy.tv.ui.theme.Dimensions

private const val HOME_POSTER_SKELETON_COUNT = 5

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
                items(HOME_POSTER_SKELETON_COUNT, contentType = { "posterSkeleton" }) {
                    Box(
                        modifier = Modifier
                            .width(Dimensions.PosterCardWidth)
                            .aspectRatio(Dimensions.PosterCardAspectRatio)
                            .skeletonElement(pulse = false)
                    )
                }
            } else {
                items(sectionUi.items, key = { "${it.type}:${it.id}" }, contentType = { "poster" }) { item ->
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
    PosterCard(
        title = item.title,
        posterUrl = item.posterUrl,
        backdropUrl = item.backdropUrl,
        rating = item.rating,
        year = item.year,
        genre = item.genre,
        logoUrl = item.logoUrl,
        poster = item.poster,
        backdrop = item.backdrop,
        logo = item.logo,
        gradientColorHex = null,
        modifier = Modifier.width(Dimensions.PosterCardWidth),
        onClick = onClick
    )
}

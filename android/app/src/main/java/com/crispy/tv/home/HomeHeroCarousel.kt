package com.crispy.tv.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.crispy.tv.ui.components.rememberCrispyImageModel
import com.crispy.tv.ui.components.skeletonElement

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

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.crispy.tv.ui.components.rememberCrispyImageModel
import com.crispy.tv.ui.components.skeletonElement
import com.crispy.tv.ui.navigation.LocalNavAnimatedContentScope
import com.crispy.tv.ui.navigation.LocalSharedTransitionScope
import com.crispy.tv.ui.navigation.animateCardCornerRadius
import com.crispy.tv.ui.navigation.animateCardOverlayAlpha

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HomeHeroSkeleton(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(320.dp)
            .skeletonElement(shape = RoundedCornerShape(28.dp), pulse = false)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.72f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .height(22.dp)
                    .skeletonElement(shape = RoundedCornerShape(4.dp), pulse = false)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .height(14.dp)
                    .skeletonElement(shape = RoundedCornerShape(4.dp), pulse = false)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(12.dp)
                    .skeletonElement(shape = RoundedCornerShape(4.dp), pulse = false)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HomeHeroCarousel(
    items: List<HomeHeroItem>,
    selectedId: String?,
    onItemClick: (HomeHeroItem, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        return
    }

    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalNavAnimatedContentScope.current

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
        modifier = modifier
            .fillMaxWidth()
            .height(320.dp)
    ) { index ->
        val item = items[index]
        val sharedElementKey = "homehero-${item.id}"
        val backdropKey = "backdrop-$sharedElementKey"
        val heroImageModel = rememberCrispyImageModel(
            image = item.artwork,
            width = 320.dp,
            height = 320.dp,
            memoryCacheKey = backdropKey,
        )

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

        val backdropModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
            val cornerRadius = with(animatedVisibilityScope) {
                animateCardCornerRadius(28.dp)
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
                    .fillMaxSize()
                    .drawWithContent {
                        drawContent()
                        if (overlayAlpha > 0.001f) {
                            drawRect(brush = bottomFadeBrush, alpha = overlayAlpha)
                        }
                    }
            }
        } else {
            Modifier.fillMaxSize()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .maskClip(RoundedCornerShape(28.dp))
                .clickable { onItemClick(item, sharedElementKey) }
        ) {
            if (heroImageModel != null) {
                AsyncImage(
                    model = heroImageModel,
                    contentDescription = item.title,
                    modifier = backdropModifier,
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

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Masks
import androidx.compose.material.icons.outlined.MoodBad
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Rocket
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SentimentVerySatisfied
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Theaters
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
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
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                val heroMeta = buildList {
                    item.genres.firstOrNull()?.let { genre -> add(genreIcon(genre) to genre) }
                    item.year?.let { add(Icons.Outlined.CalendarMonth to it) }
                    item.rating?.let { add(Icons.Filled.Star to it) }
                }

                if (heroMeta.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        heroMeta.forEachIndexed { index, (icon, label) ->
                            if (index > 0) {
                                Text(
                                    text = "•",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White.copy(alpha = 0.5f)
                                )
                            }
                            HeroMetaItem(icon = icon, label = label)
                        }
                    }
                }

                item.tagline?.takeIf { it.isNotBlank() }?.let { tagline ->
                    Text(
                        text = tagline,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun genreIcon(genre: String): ImageVector {
    return when (genre.trim().lowercase()) {
        "action" -> Icons.Outlined.Bolt
        "adventure" -> Icons.Outlined.Map
        "animated", "animation" -> Icons.Outlined.Theaters
        "comedy" -> Icons.Outlined.SentimentVerySatisfied
        "crime" -> Icons.Outlined.Gavel
        "documentary" -> Icons.Outlined.Videocam
        "drama" -> Icons.Outlined.Masks
        "family" -> Icons.Outlined.Group
        "fantasy" -> Icons.Outlined.AutoAwesome
        "horror" -> Icons.Outlined.MoodBad
        "history" -> Icons.Outlined.AccountBalance
        "music" -> Icons.Outlined.MusicNote
        "mystery" -> Icons.Outlined.Search
        "reality" -> Icons.Outlined.LiveTv
        "romance" -> Icons.Outlined.Favorite
        "scifi", "sci-fi", "science fiction", "sci-fi & fantasy", "sci fi & fantasy",
        "sci-fi and fantasy" -> Icons.Outlined.Rocket
        "sport" -> Icons.Outlined.EmojiEvents
        "thriller" -> Icons.Outlined.FlashOn
        "tv movie" -> Icons.Outlined.LiveTv
        "war" -> Icons.Outlined.Shield
        "western" -> Icons.Outlined.Movie
        else -> Icons.Outlined.Movie
    }
}

@Composable
private fun HeroMetaItem(icon: ImageVector, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = Color.White.copy(alpha = 0.8f)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.8f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
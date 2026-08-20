package com.crispy.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.crispy.tv.images.ResponsiveImageSet
import com.crispy.tv.ratings.formatRating
import com.crispy.tv.ui.navigation.LocalNavAnimatedContentScope
import com.crispy.tv.ui.navigation.LocalSharedTransitionScope
import com.crispy.tv.ui.navigation.animateCardCornerRadius
import com.crispy.tv.ui.navigation.animateCardOverlayAlpha

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LandscapeCard(
    title: String,
    backdropUrl: String?,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
    modifier: Modifier = Modifier,
    posterUrl: String? = null,
    logoUrl: String? = null,
    backdrop: ResponsiveImageSet? = null,
    poster: ResponsiveImageSet? = null,
    logo: ResponsiveImageSet? = null,
    rating: String? = null,
    year: String? = null,
    maturityRating: String? = null,
    genre: String? = null,
    itemId: String? = null,
    sharedElementKey: String? = null,
    badge: String? = null,
) {
    val fallbackColor = MaterialTheme.colorScheme.surfaceVariant
    val screenBackground = MaterialTheme.colorScheme.background
    val imageUrl = backdrop?.low ?: poster?.low ?: backdropUrl ?: posterUrl
    val resolvedLogoUrl = logo?.low ?: logoUrl
    val cardWidth = CardStyle.landscapeCardWidth()
    val cardHeight = (cardWidth.value * 9f / 16f).dp
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalNavAnimatedContentScope.current
    val resolvedKey = sharedElementKey?.takeIf { it.isNotBlank() } ?: itemId
    val backdropKey = resolvedKey?.let { "backdrop-$it" }
    val logoKey = resolvedKey?.let { "logo-$it" }
    val imageModel = crispyImageRequest(url = imageUrl, width = cardWidth, height = cardHeight, memoryCacheKey = backdropKey)
    val logoModel = crispyImageRequest(url = resolvedLogoUrl, width = 112.dp, height = 30.dp, memoryCacheKey = logoKey)

    val scrimBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                Color.Black.copy(alpha = 0.55f),
            ),
        )
    }
    val bottomFadeBrush = remember(screenBackground) {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.66f to Color.Transparent,
                1f to screenBackground,
            ),
        )
    }
    val formattedRating = remember(rating) { formatRating(rating?.toDoubleOrNull()) }
    val yearText = remember(year) { year?.trim()?.ifBlank { null } }
    val maturityText = remember(maturityRating) { maturityRating?.trim()?.ifBlank { null } }
    val genreText = remember(genre) { genre?.trim()?.ifBlank { null } }
    val metadataColor = Color.White.copy(alpha = 0.82f)
    val cardShape = RoundedCornerShape(CardStyle.CardCornerRadiusDp.dp)

    Box(
        modifier = modifier
            .width(cardWidth)
            .fillMaxWidth()
            .aspectRatio(CardStyle.LandscapeAspectRatio)
            .shadow(2.dp, cardShape)
            .clip(cardShape)
            .background(fallbackColor)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        contentAlignment = Alignment.BottomStart,
    ) {
        if (imageModel != null) {
            val backdropModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null && backdropKey != null) {
            val cornerRadius = with(animatedVisibilityScope) {
                animateCardCornerRadius(CardStyle.CardCornerRadiusDp.dp)
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
            AsyncImage(
                model = imageModel,
                contentDescription = title,
                modifier = backdropModifier,
                contentScale = ContentScale.Crop,
                onSuccess = { result ->
                    if (backdropKey != null && result.result.memoryCacheKey != null) {
                        SharedImageMemoryKeys.putCardKey(backdropKey, result.result.memoryCacheKey!!)
                    }
                },
            )
        } else {
            Text(
                text = title.take(1).uppercase(),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.50f)
                .background(scrimBrush),
        )

        if (badge != null) {
            Surface(
                shape = RoundedCornerShape(CardStyle.CardCornerRadiusDp.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            if (logoModel != null) {
                val logoModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null && logoKey != null) {
                    with(sharedTransitionScope) {
                        Modifier
                            .sharedElement(
                                rememberSharedContentState(key = logoKey),
                                animatedVisibilityScope = animatedVisibilityScope,
                            )
                            .fillMaxWidth(0.60f)
                            .height(30.dp)
                    }
                } else {
                    Modifier
                        .fillMaxWidth(0.60f)
                        .height(30.dp)
                }
                AsyncImage(
                    model = logoModel,
                    contentDescription = title,
                    modifier = logoModifier,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                )
            } else {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.96f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.widthIn(max = 240.dp),
                )
            }

            val metadataParts = buildList {
                yearText?.let { add(it) }
                maturityText?.let { add(it) }
                genreText?.let { add(it) }
                formattedRating?.let { add("★ $it") }
            }
            if (metadataParts.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = metadataParts.joinToString(separator = " · "),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = metadataColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

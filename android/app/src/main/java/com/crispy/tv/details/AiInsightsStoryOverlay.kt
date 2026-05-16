package com.crispy.tv.details

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.crispy.tv.ai.AiInsightCard
import com.crispy.tv.ai.AiInsightsResult

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun AiInsightsStoryOverlay(
    result: AiInsightsResult,
    backdropUrls: List<String>,
    onDismiss: () -> Unit,
    title: String?,
    posterUrl: String?,
    backdropUrl: String?,
    palette: DetailsPaletteColors,
    isInWatchlist: Boolean,
    onToggleWatchlist: () -> Unit,
    onShare: () -> Unit,
) {
    val slides: List<AiInsightCard> =
        remember(result) {
            val base = result.insights
            val trivia = result.trivia.trim()
            if (trivia.isBlank()) {
                base
            } else {
                base + AiInsightCard(type = "trivia", title = "Fun Fact", category = "DID YOU KNOW?", content = trivia)
            }
        }

    if (slides.isEmpty()) {
        AiInsightsEmptyStory(
            palette = palette,
            isInWatchlist = isInWatchlist,
            onToggleWatchlist = onToggleWatchlist,
            onShare = onShare,
            onDismiss = onDismiss,
        )
        return
    }

    val slideBackdrops =
        remember(slides, backdropUrls) {
            val cleaned = backdropUrls.mapNotNull(String::normalizedUrl).distinct()
            if (cleaned.isEmpty()) {
                List(slides.size) { null }
            } else {
                List(slides.size) { index -> cleaned[index % cleaned.size] }
            }
        }

    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(slides.size) {
        index = 0
    }

    val safeIndex = index.coerceIn(0, slides.lastIndex)
    val currentSlide = slides[safeIndex]
    val accentColor by animateColorAsState(
        targetValue = accentColorForType(currentSlide.type),
        animationSpec = tween(durationMillis = 320),
        label = "ai_insights_accent",
    )

    fun prev() {
        index = (safeIndex - 1).coerceAtLeast(0)
    }

    fun next() {
        if (safeIndex >= slides.lastIndex) {
            onDismiss()
        } else {
            index = safeIndex + 1
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(palette.pageBackground),
    ) {
        AiInsightsStoryBackground(
            palette = palette,
            accentColor = accentColor,
        )

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AiInsightsProgressHeader(
                slideCount = slides.size,
                index = safeIndex,
                onDismiss = onDismiss,
                palette = palette,
                accentColor = accentColor,
            )

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true)
                        .pointerInput(safeIndex, slides.size) {
                            detectTapGestures { offset ->
                                if (offset.x < size.width * 0.33f) {
                                    prev()
                                } else {
                                    next()
                                }
                            }
                        },
            ) {
                AnimatedContent(
                    targetState = safeIndex,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(180))
                    },
                    label = "ai_insights_slide",
                    modifier = Modifier.fillMaxSize(),
                ) { target ->
                    val slide = slides[target]
                    val slideAccent = accentColorForType(slide.type)
                    val slideBackdrop = slideBackdrops.getOrNull(target)
                    if (target == 0) {
                        AiInsightsHeroSlide(
                            slide = slide,
                            index = target,
                            title = title,
                            imageUrl = imageUrlForHero(
                                backdropUrl = backdropUrl,
                                slideBackdropUrl = slideBackdrop,
                                posterUrl = posterUrl,
                            ),
                            palette = palette,
                            accentColor = slideAccent,
                        )
                    } else {
                        AiInsightsDetailSlide(
                            slide = slide,
                            index = target,
                            title = title,
                            imageUrl = imageUrlForThumbnail(
                                posterUrl = posterUrl,
                                backdropUrl = backdropUrl,
                            ),
                            palette = palette,
                            accentColor = slideAccent,
                        )
                    }
                }
            }

            AiInsightsFooterActions(
                palette = palette,
                isInWatchlist = isInWatchlist,
                onToggleWatchlist = onToggleWatchlist,
                onShare = onShare,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun AiInsightsEmptyStory(
    palette: DetailsPaletteColors,
    isInWatchlist: Boolean,
    onToggleWatchlist: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(palette.pageBackground),
    ) {
        AiInsightsStoryBackground(
            palette = palette,
            accentColor = palette.accent,
        )
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AiInsightsProgressHeader(
                slideCount = 1,
                index = 0,
                onDismiss = onDismiss,
                palette = palette,
                accentColor = palette.accent,
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "AI insights unavailable",
                    style = MaterialTheme.typography.headlineSmall,
                    color = palette.onPageBackground,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            AiInsightsFooterActions(
                palette = palette,
                isInWatchlist = isInWatchlist,
                onToggleWatchlist = onToggleWatchlist,
                onShare = onShare,
            )
        }
    }
}

@Composable
private fun AiInsightsStoryBackground(
    palette: DetailsPaletteColors,
    accentColor: Color,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(palette.accent.copy(alpha = 0.24f), Color.Transparent),
                            radius = 920f,
                        ),
                    ),
        )
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(accentColor.copy(alpha = 0.18f), Color.Transparent),
                            radius = 680f,
                        ),
                    ),
        )
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.58f to palette.pageBackground.copy(alpha = 0.70f),
                            1f to palette.pageBackground,
                        ),
                    ),
        )
    }
}

@Composable
private fun AiInsightsProgressHeader(
    slideCount: Int,
    index: Int,
    onDismiss: () -> Unit,
    palette: DetailsPaletteColors,
    accentColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(slideCount.coerceAtLeast(1)) { i ->
                val fillColor =
                    if (i <= index) {
                        accentColor.copy(alpha = 0.96f)
                    } else {
                        palette.onPageBackground.copy(alpha = 0.20f)
                    }
                Box(
                    modifier =
                        Modifier
                            .height(4.dp)
                            .weight(1f)
                            .clip(CircleShape)
                            .background(fillColor),
                )
            }
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close",
                tint = palette.onPageBackground,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun AiInsightsHeroSlide(
    slide: AiInsightCard,
    index: Int,
    title: String?,
    imageUrl: String?,
    palette: DetailsPaletteColors,
    accentColor: Color,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        AiInsightsHeroArtwork(
            imageUrl = imageUrl,
            palette = palette,
            accentColor = accentColor,
        )
        Spacer(modifier = Modifier.weight(1f, fill = true))
        AiInsightsContentBlock(
            slide = slide,
            index = index,
            palette = palette,
            accentColor = accentColor,
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun AiInsightsDetailSlide(
    slide: AiInsightCard,
    index: Int,
    title: String?,
    imageUrl: String?,
    palette: DetailsPaletteColors,
    accentColor: Color,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        AiInsightsMediaHeader(
            imageUrl = imageUrl,
            title = displayTitleOrFallback(title),
            source = sourceLabelForSlide(slide),
            palette = palette,
        )
        AiInsightsOrganicShape(
            accentColor = accentColor,
            palette = palette,
            shape = blobShapeForIndex(index),
        )
        Spacer(modifier = Modifier.weight(1f, fill = true))
        AiInsightsContentBlock(
            slide = slide,
            index = index,
            palette = palette,
            accentColor = accentColor,
        )
    }
}

@Composable
private fun AiInsightsHeroArtwork(
    imageUrl: String?,
    palette: DetailsPaletteColors,
    accentColor: Color,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = 232.dp)
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(34.dp))
                .background(palette.pillBackground.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = palette.onPillBackground.copy(alpha = 0.72f),
                modifier = Modifier.size(52.dp),
            )
        }
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to accentColor.copy(alpha = 0.06f),
                            0.64f to Color.Transparent,
                            1f to palette.pageBackground.copy(alpha = 0.38f),
                        ),
                    ),
        )
    }
}

@Composable
private fun AiInsightsMediaHeader(
    imageUrl: String?,
    title: String,
    source: String,
    palette: DetailsPaletteColors,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(palette.pillBackground.copy(alpha = 0.82f)),
            contentAlignment = Alignment.Center,
        ) {
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = palette.onPillBackground.copy(alpha = 0.70f),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = palette.onPageBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = source,
                style = MaterialTheme.typography.bodySmall,
                color = palette.onPageBackground.copy(alpha = 0.68f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AiInsightsOrganicShape(
    accentColor: Color,
    palette: DetailsPaletteColors,
    shape: Shape,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(shape)
                .background(
                    Brush.linearGradient(
                        colors =
                            listOf(
                                palette.pillBackground.copy(alpha = 0.78f),
                                accentColor.copy(alpha = 0.28f),
                                palette.accent.copy(alpha = 0.18f),
                            ),
                    ),
                ),
    )
}

@Composable
private fun AiInsightsContentBlock(
    slide: AiInsightCard,
    index: Int,
    palette: DetailsPaletteColors,
    accentColor: Color,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = iconForType(slide.type),
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = sectionLabelForSlide(slide, index),
                style = MaterialTheme.typography.labelLarge,
                color = palette.onPageBackground.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "•",
                style = MaterialTheme.typography.labelLarge,
                color = palette.onPageBackground.copy(alpha = 0.48f),
            )
            Text(
                text = slide.title.trim().takeIf { it.isNotEmpty() } ?: "AI insight",
                style = MaterialTheme.typography.labelLarge,
                color = palette.onPageBackground.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = slide.content.trim(),
            style = MaterialTheme.typography.headlineSmall,
            color = palette.onPageBackground,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun AiInsightsFooterActions(
    palette: DetailsPaletteColors,
    isInWatchlist: Boolean,
    onToggleWatchlist: () -> Unit,
    onShare: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Generative AI is experimental",
            style = MaterialTheme.typography.bodySmall,
            color = palette.onPageBackground.copy(alpha = 0.62f),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AiInsightsPillButton(
                text = if (isInWatchlist) "In watchlist" else "Add to watchlist",
                palette = palette,
                onClick = onToggleWatchlist,
                modifier = Modifier.weight(1f),
            )
            AiInsightsPillButton(
                text = "Share",
                palette = palette,
                onClick = onShare,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AiInsightsPillButton(
    text: String,
    palette: DetailsPaletteColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, palette.onPageBackground.copy(alpha = 0.34f)),
        colors =
            ButtonDefaults.outlinedButtonColors(
                containerColor = Color.Transparent,
                contentColor = palette.onPageBackground,
            ),
    ) {
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun iconForType(type: String): ImageVector {
    return when (type.trim().lowercase()) {
        "consensus" -> Icons.Outlined.People
        "performance", "performance_actor" -> Icons.Outlined.FlashOn
        "theme" -> Icons.Outlined.Palette
        "vibe" -> Icons.Outlined.MusicNote
        "style" -> Icons.Outlined.Brush
        "controversy" -> Icons.Outlined.Warning
        "character" -> Icons.Outlined.Person
        "trivia" -> Icons.Outlined.Lightbulb
        else -> Icons.Outlined.AutoAwesome
    }
}

private fun displayTitleOrFallback(title: String?): String {
    return title?.trim()?.takeIf { it.isNotEmpty() } ?: "AI insights"
}

private fun sectionLabelForSlide(slide: AiInsightCard, index: Int): String {
    return slide.category.trim().takeIf { it.isNotEmpty() }
        ?: if (index == 0) "What's it about" else "AI insight"
}

private fun sourceLabelForSlide(slide: AiInsightCard): String {
    return slide.category.trim().takeIf { it.isNotEmpty() }?.let { "Crispy AI" } ?: "AI insights"
}

private fun imageUrlForHero(
    backdropUrl: String?,
    slideBackdropUrl: String?,
    posterUrl: String?,
): String? {
    return backdropUrl.normalizedUrl() ?: slideBackdropUrl.normalizedUrl() ?: posterUrl.normalizedUrl()
}

private fun imageUrlForThumbnail(
    posterUrl: String?,
    backdropUrl: String?,
): String? {
    return posterUrl.normalizedUrl() ?: backdropUrl.normalizedUrl()
}

private fun accentColorForType(type: String): Color {
    return when (type.trim().lowercase()) {
        "consensus" -> Color(0xFF63D3C1)
        "performance", "performance_actor" -> Color(0xFFFFB44C)
        "theme" -> Color(0xFFFF8A65)
        "vibe" -> Color(0xFF7CC7FF)
        "style" -> Color(0xFFFFA6B3)
        "controversy" -> Color(0xFFFF7A72)
        "character" -> Color(0xFFC4D96F)
        "trivia" -> Color(0xFFFFD166)
        else -> Color(0xFF8FB8FF)
    }
}

private fun String?.normalizedUrl(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun blobShapeForIndex(index: Int): Shape {
    return when (index % 3) {
        0 -> MaterialShapes.Arch.toShape()
        1 -> MaterialShapes.Slanted.toShape()
        else -> MaterialShapes.Bun.toShape()
    }
}

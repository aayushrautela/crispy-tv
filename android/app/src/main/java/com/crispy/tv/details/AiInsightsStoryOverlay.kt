package com.crispy.tv.details

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.SentimentVeryDissatisfied
import androidx.compose.material.icons.outlined.ThumbUp
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.crispy.tv.ai.AiInsightSlide
import com.crispy.tv.ai.AiInsightSlideKey
import com.crispy.tv.ai.AiInsightStandoutTag
import com.crispy.tv.ai.AiInsightsResult

/** Story presentation order: standout hook first, then good/bad, fun fact last. */
private val SlideDisplayOrder =
    listOf(
        AiInsightSlideKey.STANDOUT_ELEMENT,
        AiInsightSlideKey.THE_GOOD_STUFF,
        AiInsightSlideKey.THE_CATCH,
        AiInsightSlideKey.TRIVIA,
    )

private const val ShapeRotationPeriodMs = 14_000

/** Extra scale so the counter-rotated image keeps covering the shape corners. */
private const val CounterRotationOverscan = 1.45f

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun AiInsightsStoryOverlay(
    result: AiInsightsResult,
    backdropUrls: List<String>,
    onDismiss: () -> Unit,
    posterUrl: String?,
    backdropUrl: String?,
    palette: DetailsPaletteColors,
    isInWatchlist: Boolean,
    onToggleWatchlist: () -> Unit,
    onShare: () -> Unit,
) {
    val slides = remember(result) { result.slides.sortedForDisplay() }

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

    val cyclingBackdrops =
        remember(slides, backdropUrls) {
            backdropUrls.mapNotNull(String::normalizedUrl).distinct()
        }

    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(slides) {
        index = 0
    }

    val safeIndex = index.coerceIn(0, slides.lastIndex)
    val currentSlide = slides[safeIndex]
    val currentAccent = slideAccentColor(currentSlide, palette)

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
            accentColor = currentAccent,
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
                accentColor = currentAccent,
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
                        (
                            fadeIn(animationSpec = tween(durationMillis = 240)) +
                                scaleIn(initialScale = 0.985f, animationSpec = tween(durationMillis = 240))
                            ).togetherWith(fadeOut(animationSpec = tween(durationMillis = 160)))
                    },
                    label = "ai_story_slide",
                    modifier = Modifier.fillMaxSize(),
                ) { pageIndex ->
                    val slide = slides[pageIndex.coerceIn(0, slides.lastIndex)]
                    AiInsightsStorySlide(
                        slide = slide,
                        cyclingBackdropUrl =
                            if (cyclingBackdrops.isEmpty()) {
                                null
                            } else {
                                cyclingBackdrops[pageIndex % cyclingBackdrops.size]
                            },
                        posterUrl = posterUrl,
                        backdropUrl = backdropUrl,
                        palette = palette,
                    )
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
private fun AiInsightsStorySlide(
    slide: AiInsightSlide,
    cyclingBackdropUrl: String?,
    posterUrl: String?,
    backdropUrl: String?,
    palette: DetailsPaletteColors,
) {
    val imageUrl = resolveSlideImageUrl(
        slide = slide,
        cyclingBackdropUrl = cyclingBackdropUrl,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
    )
    val accentColor = slideAccentColor(slide, palette)

    when (slide.key) {
        AiInsightSlideKey.STANDOUT_ELEMENT ->
            AiInsightsStandoutSlide(
                slide = slide,
                imageUrl = imageUrl,
                palette = palette,
                accentColor = accentColor,
            )
        AiInsightSlideKey.THE_GOOD_STUFF ->
            AiInsightsMoodSlide(
                slide = slide,
                imageUrl = imageUrl,
                shape = MaterialShapes.Sunny.toShape(),
                moodIcon = Icons.Outlined.ThumbUp,
                palette = palette,
                accentColor = accentColor,
            )
        AiInsightSlideKey.THE_CATCH ->
            AiInsightsMoodSlide(
                slide = slide,
                imageUrl = imageUrl,
                shape = MaterialShapes.VerySunny.toShape(),
                moodIcon = Icons.Outlined.SentimentVeryDissatisfied,
                palette = palette,
                accentColor = accentColor,
            )
        AiInsightSlideKey.TRIVIA ->
            AiInsightsTriviaSlide(
                slide = slide,
                imageUrl = imageUrl,
                palette = palette,
                accentColor = accentColor,
            )
        AiInsightSlideKey.UNKNOWN ->
            AiInsightsMoodSlide(
                slide = slide,
                imageUrl = imageUrl,
                shape = MaterialShapes.Sunny.toShape(),
                moodIcon = Icons.Outlined.AutoAwesome,
                palette = palette,
                accentColor = accentColor,
            )
    }
}

/** Page 1: calm hero — simple rounded-rect backdrop card on top, standout text below. */
@Composable
private fun AiInsightsStandoutSlide(
    slide: AiInsightSlide,
    imageUrl: String?,
    palette: DetailsPaletteColors,
    accentColor: Color,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        AiInsightsHeroArtwork(
            imageUrl = imageUrl,
            palette = palette,
            accentColor = accentColor,
        )
        Spacer(modifier = Modifier.weight(1f, fill = true))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AiInsightsKicker(text = slide.label, palette = palette)
            slide.focus?.let { focus ->
                Text(
                    text = focus,
                    style = MaterialTheme.typography.headlineSmall,
                    color = palette.onPageBackground,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            slide.context?.let { context ->
                Text(
                    text = context,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.onPageBackground.copy(alpha = 0.80f),
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            slide.tag?.let { tag ->
                AiInsightsTagChip(
                    tag = tag,
                    palette = palette,
                    accentColor = accentColor,
                )
            }
        }
    }
}

/** Positive/negative pages: backdrop cropped into a slowly rotating material shape plus a muted mood icon. */
@Composable
private fun AiInsightsMoodSlide(
    slide: AiInsightSlide,
    imageUrl: String?,
    shape: Shape,
    moodIcon: ImageVector,
    palette: DetailsPaletteColors,
    accentColor: Color,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = true),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = moodIcon,
                contentDescription = null,
                tint = palette.onPageBackground.copy(alpha = 0.12f),
                modifier =
                    Modifier
                        .size(304.dp)
                        .offset(y = (-38).dp),
            )
            AiInsightsRotatingBackdrop(
                imageUrl = imageUrl,
                shape = shape,
                palette = palette,
                accentColor = accentColor,
                scrimAlpha = 0f,
                modifier = Modifier.size(252.dp),
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AiInsightsKicker(text = slide.label, palette = palette)
            val bodyText = slide.body ?: slide.context
            if (!bodyText.isNullOrBlank()) {
                Text(
                    text = bodyText,
                    style = MaterialTheme.typography.headlineSmall,
                    color = palette.onPageBackground,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Fun fact page: soft rotating arch over a dimmed backdrop, text centered in a pill. */
@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun AiInsightsTriviaSlide(
    slide: AiInsightSlide,
    imageUrl: String?,
    palette: DetailsPaletteColors,
    accentColor: Color,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = true),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Lightbulb,
                contentDescription = null,
                tint = palette.onPageBackground.copy(alpha = 0.12f),
                modifier =
                    Modifier
                        .size(300.dp)
                        .offset(y = (-34).dp),
            )
            AiInsightsRotatingBackdrop(
                imageUrl = imageUrl,
                shape = MaterialShapes.Arch.toShape(),
                palette = palette,
                accentColor = accentColor,
                scrimAlpha = 0.42f,
                modifier = Modifier.size(width = 240.dp, height = 280.dp),
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AiInsightsKicker(text = slide.label.ifBlank { "Did you know?" }, palette = palette)
            val bodyText = slide.body ?: slide.context
            if (!bodyText.isNullOrBlank()) {
                Box(
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(30.dp))
                            .background(palette.pillBackground.copy(alpha = 0.90f))
                            .padding(horizontal = 22.dp, vertical = 18.dp),
                ) {
                    Text(
                        text = bodyText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = palette.onPillBackground,
                        textAlign = TextAlign.Center,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Backdrop image clipped to [shape] while ONLY the clipping silhouette rotates slowly:
 * the container rotates with an infinite transition and the image counter-rotates
 * (with overscan) so its content stays perfectly still underneath the moving crop.
 */
@Composable
private fun AiInsightsRotatingBackdrop(
    imageUrl: String?,
    shape: Shape,
    palette: DetailsPaletteColors,
    accentColor: Color,
    scrimAlpha: Float,
    modifier: Modifier = Modifier,
) {
    val rotationDegrees = rememberSlowRotationDegrees()
    Box(
        modifier =
            modifier
                .graphicsLayer { rotationZ = rotationDegrees }
                .clip(shape)
                .background(
                    Brush.linearGradient(
                        colors =
                            listOf(
                                accentColor.copy(alpha = 0.30f),
                                palette.pillBackground.copy(alpha = 0.88f),
                            ),
                    ),
                ),
        contentAlignment = Alignment.Center,
    ) {
        val url = imageUrl.normalizedUrl()
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            rotationZ = -rotationDegrees
                            scaleX = CounterRotationOverscan
                            scaleY = CounterRotationOverscan
                        },
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = palette.onPillBackground.copy(alpha = 0.70f),
                modifier =
                    Modifier
                        .size(48.dp)
                        .graphicsLayer { rotationZ = -rotationDegrees },
            )
        }
        if (scrimAlpha > 0f) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(palette.pageBackground.copy(alpha = scrimAlpha)),
            )
        }
    }
}

@Composable
private fun rememberSlowRotationDegrees(): Float {
    val transition = rememberInfiniteTransition(label = "ai_insights_shape_rotation")
    val rotation =
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = ShapeRotationPeriodMs, easing = LinearEasing),
                ),
            label = "ai_insights_shape_rotation_degrees",
        )
    return rotation.value
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
private fun AiInsightsKicker(
    text: String,
    palette: DetailsPaletteColors,
) {
    val label = text.trim()
    if (label.isEmpty()) return
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = palette.onPageBackground.copy(alpha = 0.72f),
        letterSpacing = 1.4.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun AiInsightsTagChip(
    tag: AiInsightStandoutTag,
    palette: DetailsPaletteColors,
    accentColor: Color,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(accentColor.copy(alpha = 0.18f))
                .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = tag.displayLabel(),
            style = MaterialTheme.typography.labelLarge,
            color = palette.onPageBackground.copy(alpha = 0.88f),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
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

/** Reorders server slides into story order while keeping any unknown keys at the end. */
private fun List<AiInsightSlide>.sortedForDisplay(): List<AiInsightSlide> =
    sortedBy { slide -> SlideDisplayOrder.indexOf(slide.key).takeIf { it >= 0 } ?: Int.MAX_VALUE }

private fun resolveSlideImageUrl(
    slide: AiInsightSlide,
    cyclingBackdropUrl: String?,
    posterUrl: String?,
    backdropUrl: String?,
): String? {
    return (slide.backdrop.large ?: slide.backdrop.medium ?: slide.backdrop.small)?.normalizedUrl()
        ?: cyclingBackdropUrl.normalizedUrl()
        ?: backdropUrl.normalizedUrl()
        ?: posterUrl.normalizedUrl()
}

private fun slideAccentColor(slide: AiInsightSlide, fallback: DetailsPaletteColors): Color {
    val raw = slide.accent.trim()
    if (raw.isEmpty()) return fallback.accent
    val hex = raw.removePrefix("#")
    val value = hex.toLongOrNull(16) ?: return fallback.accent
    return when (hex.length) {
        6 -> Color(0xFF000000L or value)
        8 -> Color(value)
        else -> fallback.accent
    }
}

private fun AiInsightStandoutTag.displayLabel(): String =
    when (this) {
        AiInsightStandoutTag.PERFORMANCE -> "Performance"
        AiInsightStandoutTag.VISUALS -> "Visuals"
        AiInsightStandoutTag.STORY -> "Story"
        AiInsightStandoutTag.DIRECTION -> "Direction"
        AiInsightStandoutTag.WORLD_BUILDING -> "World-building"
    }

private fun String?.normalizedUrl(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

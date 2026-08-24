package com.crispy.tv.tv.ui.screens.detail

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.crispy.tv.ai.AiInsightSlide
import com.crispy.tv.ai.AiInsightSlideKey
import com.crispy.tv.ai.AiInsightStandoutTag
import com.crispy.tv.ai.AiInsightsResult

private val AiInsightsBorderColors =
    listOf(
        Color(0xFF4285F4),
        Color(0xFF34A853),
        Color(0xFFFBBC05),
        Color(0xFFEA4335),
        Color(0xFF4285F4),
    )

@Composable
private fun Modifier.aiInsightsBorderModifier(showBorder: Boolean): Modifier {
    if (!showBorder) return this

    val transition = rememberInfiniteTransition(label = "ai_insights_border")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ai_insights_border_sweep",
    )
    val glow by transition.animateFloat(
        initialValue = 0.18f,
        targetValue = 0.42f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ai_insights_border_glow",
    )

    return this.then(
        Modifier.drawWithContent {
            drawContent()

            val strokeWidth = 1.5.dp.toPx()
            val inset = strokeWidth / 2f
            val maxGlowWidth = 6.dp.toPx()
            val cornerRadius = CornerRadius(28.dp.toPx(), 28.dp.toPx())
            val brush = Brush.linearGradient(
                colors = AiInsightsBorderColors,
                start = Offset(
                    x = size.width * sweep,
                    y = size.height * sweep,
                ),
                end = Offset(
                    x = size.width * (sweep + 1f),
                    y = size.height * (sweep + 1f),
                ),
                tileMode = TileMode.Repeated,
            )

            val glowLevels = 5
            for (i in 1..glowLevels) {
                val w = maxGlowWidth * (glowLevels - i + 1) / glowLevels.toFloat()
                val off = -w / 2f
                drawRoundRect(
                    brush = brush,
                    topLeft = Offset(off, off),
                    size = Size(size.width - 2f * off, size.height - 2f * off),
                    cornerRadius = CornerRadius(28.dp.toPx() - off, 28.dp.toPx() - off),
                    style = Stroke(width = w),
                    alpha = (glow / glowLevels) * 1.5f,
                )
            }

            drawRoundRect(
                brush = brush,
                topLeft = Offset(inset, inset),
                size = Size(size.width - strokeWidth, size.height - strokeWidth),
                cornerRadius = cornerRadius,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                alpha = 0.55f,
            )
        },
    )
}

@Composable
internal fun AiInsightsButton(
    isLoading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showBorder by remember { mutableStateOf(false) }
    if (isLoading) showBorder = true

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .aiInsightsBorderModifier(showBorder)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isLoading) 0.65f else 1f),
            )
            .clickable(enabled = !isLoading, onClick = {
                showBorder = false
                onClick()
            })
            .padding(horizontal = 20.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = if (isLoading) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
            } else {
                MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "AI insights",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isLoading) 0.65f else 1f),
        )
    }
}
/** Story presentation order: standout hook first, then good/bad, fun fact last. */
private val SlideDisplayOrder =
    listOf(
        AiInsightSlideKey.STANDOUT_ELEMENT,
        AiInsightSlideKey.THE_GOOD_STUFF,
        AiInsightSlideKey.THE_CATCH,
        AiInsightSlideKey.TRIVIA,
    )

private fun List<AiInsightSlide>.sortedForDisplay(): List<AiInsightSlide> =
    sortedBy { slide -> SlideDisplayOrder.indexOf(slide.key).takeIf { it >= 0 } ?: Int.MAX_VALUE }

@Composable
internal fun AiInsightsStoryOverlay(
    result: AiInsightsResult,
    title: String,
    backdropUrl: String?,
    posterUrl: String?,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val slides = remember(result) { result.slides.sortedForDisplay() }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            .clickable(onClick = onDismiss),
    ) {
        when {
            slides.isEmpty() -> Text(
                text = "AI insights unavailable",
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            else -> StorySlides(
                slides = slides,
                title = title,
                fallbackBackdropUrl = backdropUrl,
                posterUrl = posterUrl,
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun StorySlides(
    slides: List<AiInsightSlide>,
    title: String,
    fallbackBackdropUrl: String?,
    posterUrl: String?,
    onDismiss: () -> Unit,
) {
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(slides) { index = 0 }
    val safeIndex = index.coerceIn(0, slides.lastIndex)
    val slide = slides[safeIndex]
    val accent = slide.accentColor(fallbackAccent = MaterialTheme.colorScheme.primary)

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = ScreenPadding, vertical = 24.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                repeat(slides.size) { i ->
                    Box(
                        modifier = Modifier
                            .height(4.dp)
                            .weight(1f)
                            .clip(CircleShape)
                            .background(
                                if (i <= safeIndex) {
                                    accent
                                } else {
                                    Color.White.copy(alpha = 0.20f)
                                },
                            ),
                    )
                }
            }
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close",
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier
                    .size(22.dp)
                    .clickable(onClick = onDismiss),
            )
        }

        StorySlideContent(
            slide = slide,
            imageUrl = slide.slideImageUrl(fallbackBackdropUrl = fallbackBackdropUrl, posterUrl = posterUrl),
            accent = accent,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            val prevEnabled = safeIndex > 0
            StoryActionButton(
                label = "Back",
                focusedContainer = MaterialTheme.colorScheme.primaryContainer,
                enabled = prevEnabled,
                onClick = { if (prevEnabled) index = safeIndex - 1 },
                modifier = Modifier.weight(1f),
            )
            val nextLabel = if (safeIndex >= slides.lastIndex) "Done" else "Next"
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            StoryActionButton(
                label = nextLabel,
                focusedContainer = accent,
                onClick = {
                    if (safeIndex >= slides.lastIndex) {
                        onDismiss()
                    } else {
                        index = safeIndex + 1
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
            )
        }

        Text(
            text = title,
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.45f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StorySlideContent(
    slide: AiInsightSlide,
    imageUrl: String?,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = true),
        ) {
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(24.dp)),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.25f),
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.82f),
                        ),
                    ),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.BottomStart),
            ) {
                Text(
                    text = slide.label.trim().uppercase(),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp,
                    color = accent,
                )
                val headline = slide.focus?.takeIf { it.isNotBlank() }
                val body = slide.body ?: slide.context
                if (headline != null) {
                    Text(
                        text = headline,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!body.isNullOrBlank()) {
                    Text(
                        text = body,
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        color = Color.White.copy(alpha = 0.86f),
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                slide.tag?.let { tag ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(accent.copy(alpha = 0.18f))
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = tag.displayLabel(),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.88f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StoryActionButton(
    label: String,
    focusedContainer: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(46.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(999.dp))
            .background(
                when {
                    !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    focused -> focusedContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (focused && enabled) {
                Color.White
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.5f)
            },
        )
    }
}

private fun AiInsightSlide.accentColor(fallbackAccent: Color): Color {
    val raw = accent.trim()
    if (raw.isEmpty()) return fallbackAccent
    val hex = raw.removePrefix("#")
    val value = hex.toLongOrNull(16) ?: return fallbackAccent
    return when (hex.length) {
        6 -> Color(0xFF000000L or value)
        8 -> Color(value)
        else -> fallbackAccent
    }
}

private fun AiInsightSlide.slideImageUrl(
    fallbackBackdropUrl: String?,
    posterUrl: String?,
): String? =
    (backdrop.large ?: backdrop.medium ?: backdrop.small)?.trim()?.takeIf { it.isNotEmpty() }
        ?: fallbackBackdropUrl?.trim()?.takeIf { it.isNotEmpty() }
        ?: posterUrl?.trim()?.takeIf { it.isNotEmpty() }

private fun AiInsightStandoutTag.displayLabel(): String =
    when (this) {
        AiInsightStandoutTag.PERFORMANCE -> "Performance"
        AiInsightStandoutTag.VISUALS -> "Visuals"
        AiInsightStandoutTag.STORY -> "Story"
        AiInsightStandoutTag.DIRECTION -> "Direction"
        AiInsightStandoutTag.WORLD_BUILDING -> "World-building"
    }

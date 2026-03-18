package com.crispy.tv.details

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.crispy.tv.ai.AiInsightCard
import com.crispy.tv.ai.AiInsightsResult

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun AiInsightsStoryOverlay(
    result: AiInsightsResult,
    backdropUrls: List<String>,
    onDismiss: () -> Unit,
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

    val currentSlide = slides.getOrNull(index)
    val currentBackdropUrl = slideBackdrops.getOrNull(index)
    val accentColor by animateColorAsState(
        targetValue = accentColorForType(currentSlide?.type.orEmpty()),
        animationSpec = tween(durationMillis = 320),
        label = "ai_insights_accent",
    )

    fun prev() {
        index = (index - 1).coerceAtLeast(0)
    }

    fun next() {
        if (index >= slides.lastIndex) {
            onDismiss()
        } else {
            index += 1
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color(0xFF05080E))
                .pointerInput(index, slides.size) {
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
            targetState = currentBackdropUrl,
            transitionSpec = {
                fadeIn(animationSpec = tween(280)) togetherWith fadeOut(animationSpec = tween(220))
            },
            label = "ai_insights_backdrop",
            modifier = Modifier.fillMaxSize(),
        ) { targetBackdropUrl ->
            AiInsightsBackdrop(
                imageUrl = targetBackdropUrl,
                accentColor = accentColor,
            )
        }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            AiInsightsHeader(
                slides = slides,
                index = index,
                onDismiss = onDismiss,
                accentColor = accentColor,
            )

            AnimatedContent(
                targetState = index,
                transitionSpec = {
                    fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(180))
                },
                label = "ai_insights_slide",
                modifier = Modifier.weight(1f, fill = true),
            ) { target ->
                val slide = slides[target]
                val slideAccent = accentColorForType(slide.type)
                val slideBackdrop = slideBackdrops.getOrNull(target)

                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    AiInsightsStageImage(
                        imageUrl = slideBackdrop,
                        accentColor = slideAccent,
                        shape = imageShapeForIndex(target),
                    )

                    AiInsightsSlideCard(
                        slide = slide,
                        accentColor = slideAccent,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.weight(1f, fill = true))
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Tap right to continue, left to go back",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.78f),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White.copy(alpha = 0.08f),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Warning,
                            contentDescription = null,
                            tint = accentColor.copy(alpha = 0.92f),
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "Generative AI can make mistakes. Verify important details.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.74f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AiInsightsBackdrop(
    imageUrl: String?,
    accentColor: Color,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color(0xFF05080E)),
    ) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .alpha(0.54f),
                contentScale = ContentScale.Crop,
            )
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(accentColor.copy(alpha = 0.24f), Color.Transparent),
                            radius = 900f,
                        ),
                    ),
        )

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color(0xFF08111B).copy(alpha = 0.30f),
                            0.36f to Color(0xFF09111A).copy(alpha = 0.48f),
                            0.7f to Color(0xFF071018).copy(alpha = 0.86f),
                            1f to Color(0xFF05080E),
                        ),
                    ),
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun AiInsightsStageImage(
    imageUrl: String?,
    accentColor: Color,
    shape: Shape,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(232.dp)
                .clip(shape)
                .border(width = 1.dp, color = Color.White.copy(alpha = 0.14f), shape = shape)
                .background(Color.White.copy(alpha = 0.06f)),
    ) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to accentColor.copy(alpha = 0.10f),
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.28f),
                        ),
                    ),
        )
    }
}

@Composable
private fun AiInsightsHeader(
    slides: List<AiInsightCard>,
    index: Int,
    onDismiss: () -> Unit,
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
            slides.forEachIndexed { i, _ ->
                val fillColor =
                    if (i <= index) accentColor.copy(alpha = 0.96f) else Color.White.copy(alpha = 0.22f)

                Box(
                    modifier =
                        Modifier
                            .height(5.dp)
                            .weight(1f)
                            .clip(CircleShape)
                            .background(fillColor),
                )
            }
        }

        Surface(
            color = Color.Black.copy(alpha = 0.28f),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "${index + 1}/${slides.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.82f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun AiInsightsSlideCard(
    slide: AiInsightCard,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color(0xFF101926).copy(alpha = 0.90f),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(accentColor.copy(alpha = 0.20f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = iconForType(slide.type),
                        contentDescription = null,
                        tint = accentColor,
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        color = Color.White.copy(alpha = 0.08f),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = slide.category,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.76f),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }

                    Text(
                        text = slide.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Text(
                text = slide.content,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.92f),
            )
        }
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
private fun imageShapeForIndex(index: Int): Shape {
    return when (index % 3) {
        0 -> MaterialShapes.Arch.toShape()
        1 -> MaterialShapes.Slanted.toShape()
        else -> MaterialShapes.Bun.toShape()
    }
}

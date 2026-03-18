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
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.CircleShape
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
    imageUrl: String?,
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

    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(slides.size) {
        index = 0
    }

    val currentSlide = slides.getOrNull(index)
    val accentColor by animateColorAsState(
        targetValue = accentColorForType(currentSlide?.type.orEmpty()),
        animationSpec = tween(durationMillis = 350),
        label = "ai_insights_accent",
    )

    fun prev() {
        index = (index - 1).coerceAtLeast(0)
    }

    fun next() {
        if (index >= slides.lastIndex) {
            onDismiss()
        } else {
            index = index + 1
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
        AiInsightsBackdropCollage(imageUrl = imageUrl, accentColor = accentColor)

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to accentColor.copy(alpha = 0.18f),
                            0.28f to Color(0xFF0A121B).copy(alpha = 0.38f),
                            0.68f to Color(0xFF09111A).copy(alpha = 0.82f),
                            1f to Color(0xFF05080E).copy(alpha = 0.98f),
                        ),
                    ),
        )

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

            Spacer(modifier = Modifier.height(4.dp))

            AnimatedContent(
                targetState = index,
                transitionSpec = {
                    fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(220))
                },
                label = "ai_insights_slide",
                modifier = Modifier.weight(1f, fill = true),
            ) { target ->
                val slide = slides[target]
                AiInsightsSlideCard(
                    slide = slide,
                    accentColor = accentColorForType(slide.type),
                    modifier = Modifier.fillMaxWidth(),
                )
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
                    shape = DisclaimerShape,
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
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun AiInsightsBackdropCollage(
    imageUrl: String?,
    accentColor: Color,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color(0xFF081019)),
    ) {
        if (!imageUrl.isNullOrBlank()) {
            BackdropPanel(
                imageUrl = imageUrl,
                shape = BackdropHeroShape,
                alignment = Alignment.TopEnd,
                modifier =
                    Modifier
                        .fillMaxWidth(0.82f)
                        .align(Alignment.TopEnd)
                        .padding(top = 38.dp, end = 16.dp)
                        .height(244.dp),
                accentColor = accentColor,
            )

            BackdropPanel(
                imageUrl = imageUrl,
                shape = BackdropSideShape,
                alignment = Alignment.CenterStart,
                modifier =
                    Modifier
                        .fillMaxWidth(0.48f)
                        .align(Alignment.CenterStart)
                        .padding(start = 16.dp)
                        .height(188.dp),
                accentColor = accentColor,
            )

            BackdropPanel(
                imageUrl = imageUrl,
                shape = BackdropFooterShape,
                alignment = Alignment.BottomCenter,
                modifier =
                    Modifier
                        .fillMaxWidth(0.56f)
                        .align(Alignment.BottomEnd)
                        .padding(end = 18.dp, bottom = 132.dp)
                        .height(128.dp),
                accentColor = accentColor,
            )
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.45f to Color(0xFF081019).copy(alpha = 0.22f),
                            1f to Color(0xFF081019).copy(alpha = 0.68f),
                        ),
                    ),
        )
    }
}

@Composable
private fun BoxScope.BackdropPanel(
    imageUrl: String,
    shape: Shape,
    alignment: Alignment,
    modifier: Modifier = Modifier,
    accentColor: Color,
) {
    Box(
        modifier =
            modifier
                .clip(shape)
                .border(width = 1.dp, color = Color.White.copy(alpha = 0.10f), shape = shape),
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alignment = alignment,
        )

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to accentColor.copy(alpha = 0.16f),
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.36f),
                        ),
                    ),
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
            color = Color.Black.copy(alpha = 0.24f),
            shape = ControlChipShape,
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
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun AiInsightsSlideCard(
    slide: AiInsightCard,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color(0xFF101926).copy(alpha = 0.88f),
        shape = InsightCardShape,
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
                            .clip(IconBadgeShape)
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
                        shape = CategoryChipShape,
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val BackdropHeroShape: Shape
    @Composable get() = MaterialShapes.Arch.toShape()

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val BackdropSideShape: Shape
    @Composable get() = MaterialShapes.Slanted.toShape()

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val BackdropFooterShape: Shape
    @Composable get() = MaterialShapes.Bun.toShape()

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val InsightCardShape: Shape
    @Composable get() = MaterialShapes.Arch.toShape()

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val IconBadgeShape: Shape
    @Composable get() = MaterialShapes.SoftBurst.toShape()

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val CategoryChipShape: Shape
    @Composable get() = MaterialShapes.Slanted.toShape()

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val ControlChipShape: Shape
    @Composable get() = MaterialShapes.Bun.toShape()

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val DisclaimerShape: Shape
    @Composable get() = MaterialShapes.Bun.toShape()

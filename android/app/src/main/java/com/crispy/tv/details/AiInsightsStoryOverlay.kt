package com.crispy.tv.details

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.crispy.tv.ai.AiInsightCard
import com.crispy.tv.ai.AiInsightsResult

@Composable
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
                .background(Color.Black)
                .pointerInput(index, slides.size) {
                    detectTapGestures { offset ->
                        if (offset.x < size.width * 0.33f) {
                            prev()
                        } else {
                            next()
                        }
                    }
                }
    ) {
        if (!imageUrl.isNullOrBlank() && slides.getOrNull(index)?.type != "trivia") {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )

            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.55f),
                                0.55f to Color.Black.copy(alpha = 0.9f),
                                1f to Color.Black,
                            )
                        )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        slides.forEachIndexed { i, _ ->
                            Box(
                                modifier =
                                    Modifier
                                        .height(4.dp)
                                        .weight(1f)
                                        .clip(CircleShape)
                                        .background(
                                            if (i <= index) Color.White.copy(alpha = 0.9f)
                                            else Color.White.copy(alpha = 0.25f)
                                        )
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                AnimatedContent(
                    targetState = index,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(220))
                    },
                    label = "ai_insights_slide",
                ) { target ->
                    val slide = slides[target]
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.White.copy(alpha = 0.08f),
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier =
                                        Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                            .background(Color.White.copy(alpha = 0.14f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = iconForType(slide.type),
                                        contentDescription = null,
                                        tint = Color.White,
                                    )
                                }

                                Column {
                                    Text(
                                        text = slide.category,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color.White.copy(alpha = 0.85f)
                                    )
                                    Text(
                                        text = slide.title,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }

                            Text(
                                text = slide.content,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White.copy(alpha = 0.92f)
                            )

                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Generative AI can make mistakes.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.65f)
                            )
                        }
                    }
                }
            }

            Text(
                text = "Tap right to continue, left to go back",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun iconForType(type: String): androidx.compose.ui.graphics.vector.ImageVector {
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

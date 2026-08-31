package com.crispy.tv.playerui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.crispy.tv.details.DetailsPaletteColors

/**
 * Generic bottom-sliding panel for player overlays.
 * Reusable for episodes, similar movies, etc.
 * Slides from bottom, dark scrim same as [PlayerInfoSheet] (0.5f).
 */
@Composable
internal fun PlayerBottomSheet(
    visible: Boolean,
    palette: DetailsPaletteColors,
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(180)),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = onClose,
                        ),
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(200)) + slideInVertically(animationSpec = tween(220)) { fullHeight -> fullHeight },
            exit = fadeOut(animationSpec = tween(180)) + slideOutVertically(animationSpec = tween(180)) { fullHeight -> fullHeight },
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .heightIn(max = 520.dp)
                            .widthIn(max = 900.dp)
                            .background(palette.pageBackground, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                            .navigationBarsPadding(),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.onPageBackground,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onClose) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close",
                                tint = palette.onPageBackground,
                            )
                        }
                    }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        content()
                    }
                }
            }
        }
    }
}

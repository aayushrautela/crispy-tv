package com.crispy.tv.search

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crispy.tv.ui.components.ProfileIconButton
import com.crispy.tv.ui.theme.Dimensions

private val SearchAiLoadingColors =
    listOf(
        Color(0xFF4285F4),
        Color(0xFF34A853),
        Color(0xFFFBBC05),
        Color(0xFFEA4335),
        Color(0xFF4285F4),
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onAiSearch: () -> Unit,
    onClear: () -> Unit,
    onOpenAccountsProfiles: () -> Unit,
    isAiActive: Boolean,
    isAiLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    val searchFieldShape = RoundedCornerShape(28.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                ),
            )
            .padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = Dimensions.SearchBarPillHeight)
                .then(rememberAiSearchBorderModifier(isAiLoading)),
            singleLine = true,
            shape = searchFieldShape,
            textStyle = MaterialTheme.typography.bodyLarge,
            placeholder = {
                Text(
                    text = "Find movies and shows",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                )
            },
            trailingIcon = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    AiSearchAction(
                        onClick = onAiSearch,
                        isHighlighted = isAiActive || isAiLoading,
                    )

                    if (query.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onClear),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Clear,
                                contentDescription = "Clear search",
                            )
                        }
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = if (isAiLoading) Color.Transparent else MaterialTheme.colorScheme.outline.copy(alpha = 0.85f),
                unfocusedBorderColor = if (isAiLoading) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
            ),
        )
        ProfileIconButton(onClick = onOpenAccountsProfiles)
    }
}

@Composable
private fun AiSearchAction(
    onClick: () -> Unit,
    isHighlighted: Boolean,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.AutoAwesome,
            contentDescription = "AI search",
            tint = if (isHighlighted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun rememberAiSearchBorderModifier(isAiLoading: Boolean): Modifier {
    if (!isAiLoading) return Modifier

    val borderSweepTransition = rememberInfiniteTransition(label = "ai_search_border")
    val borderSweepProgress by borderSweepTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ai_search_border_progress",
    )
    val glowAlpha by borderSweepTransition.animateFloat(
        initialValue = 0.18f,
        targetValue = 0.42f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ai_search_border_glow_alpha",
    )

    return Modifier.drawWithContent {
        drawContent()

        val strokeWidth = 2.5.dp.toPx()
        val inset = strokeWidth / 2f
        val maxGlowWidth = 14.dp.toPx()
        val cornerRadius = CornerRadius(28.dp.toPx(), 28.dp.toPx())
        val brush =
            Brush.linearGradient(
                colors = SearchAiLoadingColors,
                start = Offset(
                    x = size.width * borderSweepProgress,
                    y = size.height * borderSweepProgress
                ),
                end = Offset(
                    x = size.width * (borderSweepProgress + 1f),
                    y = size.height * (borderSweepProgress + 1f)
                ),
                tileMode = androidx.compose.ui.graphics.TileMode.Repeated
            )

        val glowLevels = 5
        for (i in 1..glowLevels) {
            val currentW = maxGlowWidth * (glowLevels - i + 1) / glowLevels.toFloat()
            val outlineOffset = -currentW / 2f

            drawRoundRect(
                brush = brush,
                topLeft = Offset(outlineOffset, outlineOffset),
                size = Size(size.width - 2 * outlineOffset, size.height - 2 * outlineOffset),
                cornerRadius = CornerRadius(28.dp.toPx() - outlineOffset, 28.dp.toPx() - outlineOffset),
                style = Stroke(width = currentW),
                alpha = (glowAlpha / glowLevels) * 1.5f,
            )
        }

        drawRoundRect(
            brush = brush,
            topLeft = Offset(inset, inset),
            size = Size(
                width = size.width - strokeWidth,
                height = size.height - strokeWidth,
            ),
            cornerRadius = cornerRadius,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
    }
}

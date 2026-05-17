package com.crispy.tv.search

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crispy.tv.ui.components.CrispySectionAppBarTitle
import com.crispy.tv.ui.components.ProfileIconButton
import com.crispy.tv.ui.components.StandardTopAppBar
import com.crispy.tv.ui.theme.Dimensions

private val SearchAiLoadingColors =
    listOf(
        Color(0xFF4285F4),
        Color(0xFF34A853),
        Color(0xFFFBBC05),
        Color(0xFFEA4335),
        Color(0xFF4285F4),
    )

@Composable
fun SearchTopBar(
    onOpenAccountsProfiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StandardTopAppBar(
        title = { CrispySectionAppBarTitle(label = "Search") },
        modifier = modifier,
        actions = {
            ProfileIconButton(onClick = onOpenAccountsProfiles)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    isAiLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .heightIn(min = Dimensions.SearchBarPillHeight)
            .then(aiSearchBorderModifier(isAiLoading)),
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
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
                modifier = Modifier.padding(start = 6.dp),
            )
        },
        trailingIcon = {
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
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = if (isAiLoading) Color.Transparent else MaterialTheme.colorScheme.outline.copy(alpha = 0.85f),
            unfocusedBorderColor = if (isAiLoading) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
        ),
    )
}

@Composable
fun AiSearchButton(
    onClick: () -> Unit,
    isHighlighted: Boolean,
    modifier: Modifier = Modifier,
) {
    val rotation by if (isHighlighted) {
        val infiniteTransition = rememberInfiniteTransition()
        infiniteTransition.animateFloat(
            initialValue = -6f,
            targetValue = 6f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 4000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "ai_sparkle_rotation",
        )
    } else {
        object : State<Float> {
            override val value = 0f
        }
    }

    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.AutoAwesome,
            contentDescription = "AI search",
            modifier = Modifier.rotate(rotation),
            tint = if (isHighlighted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun aiSearchBorderModifier(isAiLoading: Boolean): Modifier {
    if (!isAiLoading) return Modifier

    val borderSweepTransition = rememberInfiniteTransition(label = "ai_search_border")
    val borderSweepProgress by borderSweepTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ai_search_border_progress",
    )
    val glowAlpha by borderSweepTransition.animateFloat(
        initialValue = 0.18f,
        targetValue = 0.42f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ai_search_border_glow_alpha",
    )

    return Modifier.drawWithContent {
        drawContent()

        val strokeWidth = 1.5.dp.toPx()
        val inset = strokeWidth / 2f
        val maxGlowWidth = 6.dp.toPx()
        val cornerRadius = CornerRadius(28.dp.toPx(), 28.dp.toPx())
        val brush = Brush.linearGradient(
            colors = SearchAiLoadingColors,
            start = Offset(
                x = size.width * borderSweepProgress,
                y = size.height * borderSweepProgress,
            ),
            end = Offset(
                x = size.width * (borderSweepProgress + 1f),
                y = size.height * (borderSweepProgress + 1f),
            ),
            tileMode = TileMode.Repeated,
        )

        val glowLevels = 5
        for (i in 1..glowLevels) {
            val currentWidth = maxGlowWidth * (glowLevels - i + 1) / glowLevels.toFloat()
            val outlineOffset = -currentWidth / 2f

            drawRoundRect(
                brush = brush,
                topLeft = Offset(outlineOffset, outlineOffset),
                size = Size(size.width - 2 * outlineOffset, size.height - 2 * outlineOffset),
                cornerRadius = CornerRadius(28.dp.toPx() - outlineOffset, 28.dp.toPx() - outlineOffset),
                style = Stroke(width = currentWidth),
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
            alpha = 0.55f,
        )
    }
}

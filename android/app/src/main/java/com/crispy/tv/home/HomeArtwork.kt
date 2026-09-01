package com.crispy.tv.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.crispy.tv.ui.components.rememberCrispyImageModel

@Composable
internal fun LandscapeArtworkFrame(
    title: String,
    imageModel: Any?,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    badgeLabel: String? = null,
    badgeAlignment: Alignment = Alignment.TopStart,
    progressFraction: Float? = null,
    scrimHeightFraction: Float = 0.52f,
    scrimMaxAlpha: Float = 0.82f,
    topEndContent: (@Composable BoxScope.() -> Unit)? = null,
    bottomOverlayContent: @Composable BoxScope.() -> Unit = {},
    imageModifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(20.dp))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .then(
                    if (imageModel == null) {
                        Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                    } else {
                        Modifier
                    }
                ),
    ) {
        if (imageModel != null) {
            AsyncImage(
                model = imageModel,
                contentDescription = title,
                modifier = imageModifier.then(Modifier.fillMaxSize()),
                contentScale = ContentScale.Crop,
            )
        }

        HomeArtworkBottomScrim(
            heightFraction = scrimHeightFraction,
            maxAlpha = scrimMaxAlpha,
        )

        if (!badgeLabel.isNullOrBlank()) {
            Surface(
                modifier = Modifier
                    .align(badgeAlignment)
                    .padding(start = 12.dp, top = 12.dp, end = 12.dp),
                shape = RoundedCornerShape(999.dp),
                color = Color.Black.copy(alpha = 0.65f),
                contentColor = Color.White,
            ) {
                Text(
                    text = badgeLabel,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        topEndContent?.invoke(this)
        bottomOverlayContent()

        if (progressFraction != null && progressFraction > 0f) {
            val progressWidth = progressFraction.coerceIn(0f, 1f)
            val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
            val fillColor = MaterialTheme.colorScheme.onSurface
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.85f)
                    .padding(bottom = 4.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(trackColor)
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            color = fillColor,
                            size = size.copy(width = size.width * progressWidth),
                        )
                    },
            )
        }
    }
}

@Composable
internal fun BoxScope.HomeArtworkBottomScrim(
    heightFraction: Float,
    maxAlpha: Float,
) {
    Box(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth(0.7f)
            .fillMaxHeight(0.4f)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = maxAlpha),
                    ),
                ),
            ),
    )
}

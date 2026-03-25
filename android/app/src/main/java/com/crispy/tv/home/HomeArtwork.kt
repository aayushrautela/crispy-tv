package com.crispy.tv.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
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
) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(20.dp))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        if (imageModel != null) {
            AsyncImage(
                model = imageModel,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
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
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
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
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color.White.copy(alpha = 0.25f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progressFraction.coerceIn(0f, 1f))
                        .background(MaterialTheme.colorScheme.tertiary),
                )
            }
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
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .fillMaxHeight(heightFraction.coerceIn(0f, 1f))
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

@Composable
internal fun rememberPosterImageModel(url: String?): Any? {
    return rememberCrispyImageModel(url = url, width = 124.dp, height = 186.dp, tmdbSize = "w342")
}

@Composable
internal fun rememberLandscapeImageModel(url: String?, width: Dp): Any? {
    return rememberCrispyImageModel(
        url = url,
        width = width,
        height = width * (9f / 16f),
        tmdbSize = "w500",
    )
}

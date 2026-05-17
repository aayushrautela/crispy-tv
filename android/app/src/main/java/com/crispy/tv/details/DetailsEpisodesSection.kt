package com.crispy.tv.details

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.crispy.tv.home.MediaVideo
import com.crispy.tv.ui.components.rememberCrispyImageModel
import com.crispy.tv.ui.components.skeletonElement
import com.crispy.tv.ui.theme.Dimensions
import kotlin.math.roundToInt

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun EpisodeCard(
    video: MediaVideo,
    watchState: EpisodeWatchState,
    isHighlighted: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onLongPress: () -> Unit = {},
) {
    val progressFraction = (watchState.progressPercent / 100.0).coerceIn(0.0, 1.0).toFloat()
    ElevatedCard(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongPress),
        colors = CardDefaults.elevatedCardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (isHighlighted) 6.dp else 0.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(Dimensions.WideCardAspectRatio)
        ) {
            val thumbnail = video.thumbnailUrl?.trim().orEmpty()
            if (thumbnail.isNotBlank()) {
                val thumbnailModel = rememberCrispyImageModel(url = thumbnail, width = 400.dp, height = 225.dp)
                if (thumbnailModel != null) {
                    AsyncImage(
                        model = thumbnailModel,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    MaterialTheme.colorScheme.secondaryContainer
                                )
                            )
                        )
                )
            }

            val overlayScrim = remember { Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.78f)) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(overlayScrim)
            )

            val prefix = episodePrefix(video)
            if (prefix != null) {
                Text(
                    text = prefix,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.92f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (watchState.isWatched) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.94f),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "Watched",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            } else if (watchState.progressPercent > 0.0) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.94f),
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Text(
                        text = "${watchState.progressPercent.roundToInt()}%",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                video.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                    Text(
                        text = overview,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.78f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                formatLongDate(video.released)?.let { released ->
                    Text(
                        text = released,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.82f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (!watchState.isWatched && watchState.progressPercent > 0.0) {
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
                            .fillMaxWidth(progressFraction)
                            .background(MaterialTheme.colorScheme.tertiary),
                    )
                }
            }
        }
    }
}

@Composable
internal fun EpisodeCardSkeleton(
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(Dimensions.WideCardAspectRatio)
                    .skeletonElement(color = DetailsSkeletonColors.Base)
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth(0.75f)
                            .height(18.dp)
                            .skeletonElement(
                                shape = RoundedCornerShape(6.dp),
                                color = DetailsSkeletonColors.Elevated,
                            )
                )
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(34.dp)
                            .skeletonElement(
                                shape = RoundedCornerShape(6.dp),
                                color = DetailsSkeletonColors.Elevated,
                            )
                )
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth(0.4f)
                            .height(14.dp)
                            .skeletonElement(
                                shape = RoundedCornerShape(6.dp),
                                color = DetailsSkeletonColors.Elevated,
                            )
                )
            }
        }
    }
}

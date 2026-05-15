package com.crispy.tv.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.home.LandscapeArtworkFrame
import com.crispy.tv.home.rememberLandscapeImageModel

private val MAKING_OF_VIDEO_TYPES = setOf("Behind the Scenes", "Bloopers")

@Composable
internal fun MakingOfVideosSection(
    videos: List<CrispyBackendClient.MetadataVideoView>,
    baseTitle: String,
    horizontalPadding: Dp,
    contentPadding: PaddingValues,
    onVideoClick: (CrispyBackendClient.MetadataVideoView) -> Unit,
) {
    val filtered = videos
        .filter { it.key.isNotBlank() && it.type in MAKING_OF_VIDEO_TYPES }
        .sortedByDescending { it.type.equals("Bloopers", ignoreCase = true) }

    if (filtered.isEmpty()) return

    Spacer(modifier = Modifier.height(18.dp))
    Text(
        text = "Making of $baseTitle",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = horizontalPadding),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(modifier = Modifier.height(10.dp))
    LazyRow(
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items = filtered, key = { it.id }) { video ->
            MakingOfCard(video = video, onClick = { onVideoClick(video) })
        }
    }
}

@Composable
private fun MakingOfCard(
    video: CrispyBackendClient.MetadataVideoView,
    onClick: () -> Unit,
) {
    val imageModel = rememberLandscapeImageModel(video.thumbnailUrl, 280.dp)
    LandscapeArtworkFrame(
        title = video.name.orEmpty(),
        imageModel = imageModel,
        onClick = onClick,
        modifier = Modifier
            .width(280.dp)
            .aspectRatio(16f / 9f),
        scrimHeightFraction = 0.55f,
        scrimMaxAlpha = 0.88f,
        bottomOverlayContent = {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = video.name.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    )
}

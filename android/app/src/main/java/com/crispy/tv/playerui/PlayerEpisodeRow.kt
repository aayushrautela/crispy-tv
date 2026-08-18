package com.crispy.tv.playerui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crispy.tv.details.DetailsPaletteColors
import com.crispy.tv.home.MediaVideo
import com.crispy.tv.streams.formatEpisodeReleaseDate

@Composable
internal fun EpisodeRow(
    episode: MediaVideo,
    isCurrent: Boolean,
    palette: DetailsPaletteColors,
    onClick: () -> Unit,
) {
    ElevatedCard(
        onClick = onClick,
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = palette.pillBackground,
                contentColor = palette.onPillBackground,
            ),
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = episode.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else null,
                    color = if (isCurrent) palette.accent else palette.onPillBackground,
                )
            },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    episodeRowMeta(episode)?.let { text ->
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.onPillBackground.copy(alpha = 0.7f),
                        )
                    }
                    episode.overview?.trim()?.takeIf { it.isNotBlank() }?.let { overview ->
                        Text(
                            text = overview,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            color = palette.onPillBackground.copy(alpha = 0.8f),
                        )
                    }
                }
            },
            trailingContent =
                if (isCurrent) {
                    {
                        StaticTag(
                            text = "Now Playing",
                            emphasized = true,
                            palette = palette,
                        )
                    }
                } else {
                    null
                },
        )
    }
}

internal fun episodeRowMeta(episode: MediaVideo): String? {
    val parts = mutableListOf<String>()
    val season = episode.season
    val episodeNumber = episode.episode
    if (season != null && episodeNumber != null) {
        parts += "S$season E$episodeNumber"
    }
    formatEpisodeReleaseDate(episode.released)?.let(parts::add)
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
}

@Composable
internal fun StaticTag(
    text: String,
    emphasized: Boolean = false,
    palette: DetailsPaletteColors,
) {
    val containerColor = if (emphasized) palette.accent else palette.pillBackground
    val contentColor = if (emphasized) palette.onAccent else palette.onPillBackground

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

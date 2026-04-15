package com.crispy.tv.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crispy.tv.ui.components.rememberCrispyImageModel

@Composable
internal fun CalendarEpisodeCard(
    item: CalendarEpisodeItem,
    onClick: () -> Unit,
) {
    val imageModel = rememberLandscapeImageModel(item.thumbnailUrl ?: item.backdropUrl ?: item.posterUrl, 280.dp)
    Column(
        modifier = Modifier.width(280.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LandscapeArtworkFrame(
            title = item.seriesName,
            imageModel = imageModel,
            onClick = onClick,
            modifier = Modifier.aspectRatio(16f / 9f),
            badgeLabel = calendarBadgeLabel(item),
            badgeAlignment = Alignment.TopEnd,
            scrimHeightFraction = 0.68f,
            scrimMaxAlpha = 0.92f,
            bottomOverlayContent = {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = item.seriesName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = calendarSecondaryText(item),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
        )
    }
}

@Composable
internal fun CalendarSeriesCard(
    item: CalendarSeriesItem,
    onClick: () -> Unit,
) {
    val imageModel = rememberLandscapeImageModel(item.backdropUrl ?: item.posterUrl, 280.dp)
    Column(
        modifier = Modifier.width(280.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LandscapeArtworkFrame(
            title = item.title,
            imageModel = imageModel,
            onClick = onClick,
            modifier = Modifier.aspectRatio(16f / 9f),
            badgeLabel = "No schedule",
            bottomOverlayContent = {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    item.sourceLabel?.takeIf { it.isNotBlank() }?.let { sourceLabel ->
                        Text(
                            text = sourceLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
        )
    }
}

private fun calendarBadgeLabel(item: CalendarEpisodeItem): String? {
    if (item.isReleased) return "Released"
    val releaseDate = item.releaseDate ?: return null
    return try {
        val date = java.time.LocalDate.parse(releaseDate.take(10))
        "${date.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${date.dayOfMonth}"
    } catch (_: Exception) {
        null
    }
}

private fun calendarEpisodeLabel(item: CalendarEpisodeItem): String {
    val season = item.season
    val episode = item.episode
    return when {
        item.episodeRange != null && season != null -> "S$season ${item.episodeRange}"
        season != null && episode != null -> "S$season E$episode"
        item.episodeRange != null -> item.episodeRange
        episode != null -> "Episode $episode"
        item.releaseDate != null -> item.releaseDate.take(10)
        else -> "Upcoming episode"
    }
}

private fun calendarSecondaryText(item: CalendarEpisodeItem): String {
    val supportingText = when {
        item.isGroup -> "${item.episodeCount} new episodes"
        !item.episodeTitle.isNullOrBlank() -> item.episodeTitle
        !item.overview.isNullOrBlank() -> item.overview
        else -> null
    }?.trim()

    val episodeLabel = calendarEpisodeLabel(item)
    return if (supportingText.isNullOrBlank()) {
        episodeLabel
    } else {
        "$episodeLabel - $supportingText"
    }
}

package com.crispy.tv.playerui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.crispy.tv.details.DetailsPaletteColors
import com.crispy.tv.details.ExpandableDescription
import com.crispy.tv.details.formatRuntimeForHeader
import com.crispy.tv.home.MediaDetails
import com.crispy.tv.home.MediaVideo
import com.crispy.tv.ratings.normalizeRatingText

@Composable
internal fun PlayerInfoSheet(
    visible: Boolean,
    details: MediaDetails?,
    palette: DetailsPaletteColors,
    onClose: () -> Unit,
    headerEpisode: MediaVideo? = null,
) {
    Box(modifier = Modifier.fillMaxSize()) {
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
            enter = fadeIn(animationSpec = tween(200)) + slideInHorizontally(animationSpec = tween(200)) { fullWidth -> fullWidth },
            exit = fadeOut(animationSpec = tween(180)) + slideOutHorizontally(animationSpec = tween(180)) { fullWidth -> fullWidth },
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Surface(
                    modifier =
                        Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .fillMaxWidth(0.4f)
                            .widthIn(max = 400.dp),
                    color = palette.pageBackground,
                    contentColor = palette.onPageBackground,
                    shadowElevation = 0.dp,
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        InfoSheetContent(
                            details = details,
                            palette = palette,
                            headerEpisode = headerEpisode,
                        )

                        IconButton(
                            onClick = onClose,
                            modifier =
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                                    .background(palette.pageBackground.copy(alpha = 0.55f), CircleShape)
                                    .padding(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close",
                                tint = palette.onPageBackground,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoSheetContent(
    details: MediaDetails?,
    palette: DetailsPaletteColors,
    headerEpisode: MediaVideo? = null,
) {
    val showCast = details?.cast?.any { it.isNotBlank() } == true
    val episodeContext = details?.toPlayerEpisodeContext() ?: headerEpisode?.toPlayerEpisodeContext()
    val showEpisode = episodeContext != null
    val creditLine = buildCreditLine(details)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { TitleArea(details = details, palette = palette) }
        item { MetaRow(details = details, palette = palette) }
        if (showEpisode) {
            item { EpisodeContextBlock(episodeContext = episodeContext, palette = palette) }
        }
        item { OverviewBlock(episodeContext = episodeContext, details = details, palette = palette) }
        if (showCast) {
            item { CastBlock(details = details, palette = palette) }
        }
        if (creditLine != null) {
            item {
                Text(
                    text = creditLine,
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.onPageBackground.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun TitleArea(
    details: MediaDetails?,
    palette: DetailsPaletteColors,
) {
    val logoUrl = details?.logoUrl?.trim()?.takeIf { it.isNotBlank() }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (logoUrl != null) {
            Box(modifier = Modifier.fillMaxWidth(0.81f)) {
                AsyncImage(
                    model = logoUrl,
                    contentDescription = details.title,
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .heightIn(min = 72.dp, max = 120.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                )
            }
        } else {
            Text(
                text = details?.title ?: "",
                style = MaterialTheme.typography.headlineMedium,
                color = palette.onPageBackground,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun MetaRow(
    details: MediaDetails?,
    palette: DetailsPaletteColors,
) {
    val rating = normalizeRatingText(details?.rating)
    val certification = details?.certification?.trim()?.takeIf { it.isNotBlank() }
    val year = details?.year?.trim()?.takeIf { it.isNotBlank() }
    val runtime = formatRuntimeForHeader(details?.runtime)
    val genres = details?.genres?.filter { it.isNotBlank() }.orEmpty().take(2)

    if (rating == null && certification == null && year == null && runtime == null && genres.isEmpty()) return

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        if (rating != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = Color(0xFFFFD54F),
                )
                Text(
                    text = rating,
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.onPageBackground,
                )
            }
        }
        if (year != null) {
            Text(
                text = year,
                style = MaterialTheme.typography.labelLarge,
                color = palette.onPageBackground.copy(alpha = 0.86f),
            )
        }
        if (certification != null) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = palette.pillBackground,
                contentColor = palette.onPillBackground,
                border = BorderStroke(1.dp, palette.onPillBackground.copy(alpha = 0.3f)),
            ) {
                Text(
                    text = certification,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        genres.forEach { genre ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = palette.pillBackground,
                contentColor = palette.onPillBackground,
                border = BorderStroke(1.dp, palette.onPillBackground.copy(alpha = 0.25f)),
            ) {
                Text(
                    text = genre,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        if (runtime != null) {
            Text(
                text = runtime,
                style = MaterialTheme.typography.labelLarge,
                color = palette.onPageBackground.copy(alpha = 0.86f),
            )
        }
    }
}

@Composable
private fun EpisodeContextBlock(
    episodeContext: PlayerEpisodeContext?,
    palette: DetailsPaletteColors,
) {
    val context = episodeContext ?: return
    val title = context.title?.trim()?.takeIf { it.isNotBlank() }

    Text(
        text =
            buildString {
                append("Now Playing · ${context.seasonEpisodeLabel}")
                if (title != null) append(" — $title")
            },
        style = MaterialTheme.typography.bodyMedium,
        color = palette.onPageBackground.copy(alpha = 0.8f),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun OverviewBlock(
    episodeContext: PlayerEpisodeContext?,
    details: MediaDetails?,
    palette: DetailsPaletteColors,
) {
    val description =
        episodeContext?.overview
            ?: details?.description?.trim()?.takeIf { it.isNotBlank() }
            ?: return
    ExpandableDescription(
        text = description,
        textAlign = TextAlign.Center,
        textColor = palette.onPageBackground.copy(alpha = 0.9f),
    )
}

@Composable
private fun CastBlock(
    details: MediaDetails?,
    palette: DetailsPaletteColors,
) {
    val cast = details?.cast?.filter { it.isNotBlank() }.orEmpty().take(5)
    if (cast.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Cast",
            style = MaterialTheme.typography.titleSmall,
            color = palette.accent,
        )
        cast.forEach { entry ->
            val (name, character) = parseCastEntry(entry)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.onPageBackground,
                )
                if (character != null) {
                    Text(
                        text = "as $character",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.onPageBackground.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

private fun parseCastEntry(entry: String): Pair<String, String?> {
    val separator = entry.indexOf(" as ")
    if (separator < 0) return entry to null
    val name = entry.substring(0, separator).trim()
    val character = entry.substring(separator + 4).trim().takeIf { it.isNotBlank() }
    return name to character
}

private fun buildCreditLine(details: MediaDetails?): String? {
    val isMovie = details?.itemType.equals("movie", ignoreCase = true)
    return if (isMovie) {
        details
            ?.directors
            ?.filter { it.isNotBlank() }
            ?.joinToString(", ")
            ?.takeIf { it.isNotBlank() }
            ?.let { "Directed by $it" }
    } else {
        details
            ?.creators
            ?.filter { it.isNotBlank() }
            ?.joinToString(", ")
            ?.takeIf { it.isNotBlank() }
            ?.let { "Created by $it" }
    }
}

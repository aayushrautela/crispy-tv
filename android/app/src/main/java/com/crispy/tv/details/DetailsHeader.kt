package com.crispy.tv.details

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.crispy.tv.home.MediaDetails
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import kotlin.math.roundToInt

@Composable
internal fun HeaderInfoSection(
    details: MediaDetails?,
    isInWatchlist: Boolean,
    isWatched: Boolean,
    isRated: Boolean,
    userRating: Int?,
    isMutating: Boolean,
    palette: DetailsPaletteColors,
    showAiInsights: Boolean,
    aiInsightsEnabled: Boolean,
    aiInsightsIsLoading: Boolean,
    onAiInsightsClick: () -> Unit,
    onWatchNow: () -> Unit,
    onToggleWatchlist: () -> Unit,
    onToggleWatched: () -> Unit,
    onSetRating: (Int?) -> Unit,
) {
    if (details == null) return

    val horizontalPadding = responsivePageHorizontalPadding()
    val genre = details.genres.firstOrNull()?.trim().orEmpty()

    var showRatingDialog by rememberSaveable { mutableStateOf(false) }
    var pendingRating by rememberSaveable { mutableStateOf(0f) }

    if (showRatingDialog) {
        AlertDialog(
            onDismissRequest = { showRatingDialog = false },
            title = { Text("Rate") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = if (pendingRating.roundToInt() == 0) "No rating" else "${pendingRating.roundToInt()}/10",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Slider(
                        value = pendingRating,
                        onValueChange = { pendingRating = it },
                        valueRange = 0f..10f,
                        steps = 9
                    )

                    TextButton(
                        onClick = {
                            onSetRating(null)
                            showRatingDialog = false
                        },
                        enabled = pendingRating.roundToInt() != 0
                    ) {
                        Text("Clear")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ratingInt = pendingRating.roundToInt().coerceIn(0, 10)
                        onSetRating(if (ratingInt == 0) null else ratingInt)
                        showRatingDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showRatingDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
            .padding(top = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (genre.isNotBlank()) {
            Text(
                text = genre,
                style = MaterialTheme.typography.labelLarge,
                color = palette.onPageBackground.copy(alpha = 0.86f)
            )
        }

        HeaderMetaRow(details = details, palette = palette)

        ExpandableDescription(
            text = details.description,
            textAlign = TextAlign.Center,
            textColor = palette.onPageBackground.copy(alpha = 0.9f),
            placeholderColor = palette.onPageBackground.copy(alpha = 0.7f)
        )

        if (showAiInsights) {
            FilledTonalButton(
                onClick = onAiInsightsClick,
                enabled = !aiInsightsIsLoading,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                shape = MaterialTheme.shapes.extraLarge,
                colors =
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = palette.pillBackground,
                        contentColor = palette.onPillBackground,
                        disabledContainerColor = palette.pillBackground.copy(alpha = 0.65f),
                        disabledContentColor = palette.onPillBackground.copy(alpha = 0.65f)
                    )
            ) {
                if (aiInsightsIsLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = palette.onPillBackground,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null
                    )
                }
                Spacer(modifier = Modifier.size(10.dp))
                Text(if (aiInsightsEnabled) "AI insights" else "Set up AI insights")
            }
        }

        Button(
            onClick = onWatchNow,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = MaterialTheme.shapes.extraLarge,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = palette.accent,
                    contentColor = palette.onAccent
                )
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null
            )
            Spacer(modifier = Modifier.size(10.dp))
            Text("Watch now")
        }

        DetailsQuickActionsRow(
            palette = palette,
            enabled = !isMutating,
            isInWatchlist = isInWatchlist,
            isWatched = isWatched,
            showWatched = details.mediaType != "series",
            isRated = isRated,
            userRating = userRating,
            onToggleWatchlist = onToggleWatchlist,
            onToggleWatched = onToggleWatched,
            onRate = {
                pendingRating = (userRating ?: 0).toFloat()
                showRatingDialog = true
            }
        )

        Spacer(modifier = Modifier.height(2.dp))
    }
}

@Composable
private fun DetailsQuickActionsRow(
    palette: DetailsPaletteColors,
    enabled: Boolean,
    isInWatchlist: Boolean,
    isWatched: Boolean,
    showWatched: Boolean,
    isRated: Boolean,
    userRating: Int?,
    onToggleWatchlist: () -> Unit,
    onToggleWatched: () -> Unit,
    onRate: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        DetailsQuickAction(
            label = "Watchlist",
            selected = isInWatchlist,
            enabled = enabled,
            palette = palette,
            icon = if (isInWatchlist) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
            onClick = onToggleWatchlist
        )

        if (showWatched) {
            DetailsQuickAction(
                label = "Watched",
                selected = isWatched,
                enabled = enabled,
                palette = palette,
                icon = if (isWatched) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircleOutline,
                onClick = onToggleWatched
            )
        }

        val gold = Color(0xFFFFD700)
        DetailsQuickAction(
            label = if (isRated) (userRating?.let { "Rated $it" } ?: "Rated") else "Rate",
            selected = isRated,
            enabled = enabled,
            palette = palette,
            selectedAccent = gold,
            icon = if (isRated) Icons.Filled.Star else Icons.Outlined.StarBorder,
            onClick = onRate
        )
    }
}

@Composable
private fun DetailsQuickAction(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    palette: DetailsPaletteColors,
    selectedAccent: Color? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    val accent = selectedAccent ?: palette.accent
    val container = if (selected) lerp(palette.pillBackground, accent, 0.28f) else palette.pillBackground
    val iconTint = if (selected) accent else palette.onPillBackground.copy(alpha = 0.92f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = 6.dp)
    ) {
        Surface(
            modifier = Modifier
                .size(48.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .clickable(enabled = enabled) { onClick() },
            color = container,
            contentColor = palette.onPillBackground
        ) {
            androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = palette.onPageBackground.copy(alpha = if (enabled) 0.9f else 0.55f)
        )
    }
}

@Composable
private fun HeaderMetaRow(
    details: MediaDetails,
    palette: DetailsPaletteColors
) {
    val rating = details.rating?.trim().takeIf { !it.isNullOrBlank() }
    val certification = details.certification?.trim().takeIf { !it.isNullOrBlank() }
    val year = details.year?.trim().takeIf { !it.isNullOrBlank() }
    val runtime = formatRuntimeForHeader(details.runtime)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (rating != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = Color(0xFFFFD54F)
                )
                Text(
                    text = rating,
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.onPageBackground
                )
            }
        }

        if (certification != null) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = palette.pillBackground,
                contentColor = palette.onPillBackground
            ) {
                Text(
                    text = certification,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        if (year != null) {
            Text(
                text = year,
                style = MaterialTheme.typography.labelLarge,
                color = palette.onPageBackground.copy(alpha = 0.86f)
            )
        }

        if (runtime != null) {
            Text(
                text = runtime,
                style = MaterialTheme.typography.labelLarge,
                color = palette.onPageBackground.copy(alpha = 0.86f)
            )
        }
    }
}

@Composable
internal fun ExpandableDescription(
    text: String?,
    textAlign: TextAlign = TextAlign.Start,
    textColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
    placeholderColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    val content = text?.trim().orEmpty()
    if (content.isBlank()) {
        Text(
            text = "No description available.",
            style = MaterialTheme.typography.bodyMedium,
            color = placeholderColor,
            textAlign = textAlign
        )
        return
    }

    var textLayoutResult by remember(content) { mutableStateOf<TextLayoutResult?>(null) }
    val displayContent = remember(content, expanded, textLayoutResult) {
        val layout = textLayoutResult
        if (expanded) {
            buildAnnotatedString {
                append(content)
                append(" ")
                withStyle(SpanStyle(color = textColor.copy(alpha = 0.64f), fontWeight = FontWeight.Bold)) {
                    append("Show less")
                }
            }
        } else if (layout != null && layout.hasVisualOverflow) {
            val lineEnd = layout.getLineEnd(2, true)
            buildAnnotatedString {
                append(content.substring(0, lineEnd).dropLast(12).trim())
                append("... ")
                withStyle(SpanStyle(color = textColor.copy(alpha = 0.9f), fontWeight = FontWeight.Bold)) {
                    append("Show more")
                }
            }
        } else {
            buildAnnotatedString { append(content) }
        }
    }

    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { expanded = !expanded }
    ) {
        Text(
            text = displayContent,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            textAlign = textAlign,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (textLayoutResult == null) textLayoutResult = it }
        )
    }
}

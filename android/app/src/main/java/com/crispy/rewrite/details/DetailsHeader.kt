package com.crispy.rewrite.details

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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.crispy.rewrite.home.MediaDetails
import com.crispy.rewrite.ui.theme.responsivePageHorizontalPadding

@Composable
internal fun HeaderInfoSection(
    details: MediaDetails?,
    isInWatchlist: Boolean,
    isMutating: Boolean,
    palette: DetailsPaletteColors,
    onToggleWatchlist: () -> Unit,
) {
    if (details == null) return

    val horizontalPadding = responsivePageHorizontalPadding()
    val genre = details.genres.firstOrNull()?.trim().orEmpty()

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

        DetailsQuickActionsRow(
            palette = palette,
            enabled = !isMutating,
            isInWatchlist = isInWatchlist,
            onToggleWatchlist = onToggleWatchlist
        )

        Spacer(modifier = Modifier.height(2.dp))
    }
}

@Composable
private fun DetailsQuickActionsRow(
    palette: DetailsPaletteColors,
    enabled: Boolean,
    isInWatchlist: Boolean,
    onToggleWatchlist: () -> Unit,
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
    }
}

@Composable
private fun DetailsQuickAction(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    palette: DetailsPaletteColors,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    val container = if (selected) lerp(palette.pillBackground, palette.accent, 0.28f) else palette.pillBackground
    val iconTint = if (selected) palette.accent else palette.onPillBackground.copy(alpha = 0.92f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = 6.dp)
    ) {
        Surface(
            modifier = Modifier
                .size(52.dp)
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

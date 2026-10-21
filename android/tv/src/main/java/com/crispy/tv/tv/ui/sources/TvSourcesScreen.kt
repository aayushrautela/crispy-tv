package com.crispy.tv.tv.ui.sources

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.crispy.tv.tv.sources.SourceRowUi
import com.crispy.tv.tv.sources.SourcesUiState

private val PanelPadding = 48.dp

/**
 * The stream picker.
 *
 * ## Why every row is focusable
 *
 * The rows used to be plain clickable rows, which on a television means **rows the
 * viewer cannot land on**: the D-pad moved the selection, the selection was never
 * drawn, and the viewer had no way of telling which row the centre key would pick.
 * Each row is now a focus target that says so -- it grows a little and draws a
 * border when it has the focus -- and the list scrolls itself as the selection
 * moves.
 */
@Composable
fun TvSourcesScreen(
    state: SourcesUiState,
    onPick: (SourceRowUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = PanelPadding, vertical = 28.dp),
    ) {
        Text(
            text = "Select source",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        val progress = when {
            state.loading -> "Resolving providers…"
            state.providersResolved > 0 -> "${state.providersWithResults}/${state.providersResolved} providers responded"
            else -> null
        }
        if (progress != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = progress,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.notice?.let { notice ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = notice,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 14.sp,
            )
        }
        Spacer(Modifier.height(20.dp))

        when {
            !state.loading && !state.hasAnyRows -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No sources found for this title.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.rows, key = { it.id }) { row ->
                        SourceRow(row = row, onPick = onPick)
                    }
                    if (state.loading) {
                        item(key = "loading") {
                            Text(
                                text = "Loading more providers…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(
    row: SourceRowUi,
    onPick: (SourceRowUi) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.03f else 1f,
        label = "source-row-scale",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onPick(row) }
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 2.dp,
                color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            )
            .background(
                if (focused) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .width(120.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(
                text = row.providerName,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (row.isPlayable) {
                    MaterialTheme.colorScheme.onBackground
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!row.description.isNullOrBlank()) {
                Text(
                    text = row.description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        if (row.subtitleCount > 0) {
            SourceBadge(text = "${row.subtitleCount} subs")
            Spacer(Modifier.width(6.dp))
        }
        val badge = when {
            row.cached -> "Cached"
            // A torrent plays here: the engine resolves the magnet link when the
            // player starts. The badge says what the row is, not that it is refused.
            row.isTorrent -> "Torrent"
            else -> null
        }
        if (badge != null) {
            SourceBadge(text = badge)
        }
    }
}

@Composable
private fun SourceBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

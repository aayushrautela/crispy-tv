package com.crispy.tv.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Single shared shell for the long-press / item-actions sheet used across the
 * home rails and the library. The visual treatment (title, optional subtitle,
 * full-width action rows) is identical everywhere; each caller supplies its own
 * actions so behaviour stays contextual.
 */
data class CardActionSheetItem(
    val label: String,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
)

@Composable
fun CardActionSheet(
    title: String,
    subtitle: String? = null,
    items: List<CardActionSheetItem>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }
        items.forEach { action ->
            val contentColor = if (action.destructive) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            }
            ListItem(
                headlineContent = {
                    Text(text = action.label, color = contentColor)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = action.onClick),
            )
        }
    }
}

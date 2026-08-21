package com.crispy.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.crispy.tv.ui.components.rememberCrispyImageModel

/**
 * Shared long-press / item-actions sheet shell used across the app (library,
 * home wide rails, episode cards). Every caller supplies its own header content
 * and actions so behaviour stays contextual while the visual treatment stays
 * identical.
 */
data class ItemActionSheetItem(
    val label: String,
    val supporting: String? = null,
    val icon: ImageVector? = null,
    val filled: Boolean = false,
    val destructive: Boolean = false,
    val dividerBefore: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
fun ItemActionSheet(
    title: String,
    subtitle: String? = null,
    imageUrl: String? = null,
    actions: List<ItemActionSheetItem>,
    modifier: Modifier = Modifier,
) {
    val rawUrl = imageUrl?.trim()?.takeIf { it.isNotBlank() }
    val imageModel = rawUrl?.let { rememberCrispyImageModel(url = it, width = 96.dp, height = 56.dp) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
                if (rawUrl != null) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        AsyncImage(
                            model = imageModel ?: rawUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(width = 96.dp, height = 56.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        actions.forEach { action ->
            if (action.dividerBefore) {
                HorizontalDivider()
            }
            WatchActionRow(
                label = action.label,
                supporting = action.supporting.orEmpty(),
                icon = action.icon,
                filled = action.filled,
                destructive = action.destructive,
                onClick = action.onClick,
            )
        }
    }

@Composable
fun WatchActionRow(
    label: String,
    supporting: String,
    icon: ImageVector?,
    filled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val accent = when {
        destructive -> MaterialTheme.colorScheme.error
        filled -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (supporting.isNotBlank()) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    color = if (filled) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = CircleShape,
                ).then(
                    if (!filled) {
                        Modifier.border(width = 1.5.dp, color = accent, shape = CircleShape)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (filled) MaterialTheme.colorScheme.onPrimary else accent,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

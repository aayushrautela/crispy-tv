package com.crispy.tv.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class CrispyViewAllPillSize {
    Default,
    Compact,
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> CrispyShelfSection(
    title: String,
    entries: List<T>,
    modifier: Modifier = Modifier,
    headerHorizontalPadding: Dp = 0.dp,
    rowContentPadding: PaddingValues = PaddingValues(0.dp),
    itemSpacing: Dp = 12.dp,
    showHeaderAccent: Boolean = true,
    onViewAllClick: (() -> Unit)? = null,
    viewAllPillSize: CrispyViewAllPillSize = CrispyViewAllPillSize.Default,
    key: ((T) -> Any)? = null,
    animatePlacement: Boolean = false,
    state: LazyListState = rememberLazyListState(),
    itemContent: @Composable (T) -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (title.isNotBlank()) {
            CrispyShelfSectionHeader(
                title = title,
                modifier = Modifier.padding(horizontal = headerHorizontalPadding),
                showAccent = showHeaderAccent,
                onViewAllClick = onViewAllClick,
                viewAllPillSize = viewAllPillSize,
            )
        }
        LazyRow(
            state = state,
            contentPadding = rowContentPadding,
            horizontalArrangement = Arrangement.spacedBy(itemSpacing),
        ) {
            if (key != null) {
                items(
                    items = entries.withDuplicateSafeLazyKeys(key),
                    key = { entry -> entry.lazyKey },
                ) { keyedEntry ->
                    if (animatePlacement) {
                        Box(modifier = Modifier.animateItem()) { itemContent(keyedEntry.value) }
                    } else {
                        itemContent(keyedEntry.value)
                    }
                }
            } else {
                items(entries) { entry ->
                    if (animatePlacement) {
                        Box(modifier = Modifier.animateItem()) { itemContent(entry) }
                    } else {
                        itemContent(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun CrispyShelfSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    showAccent: Boolean = true,
    onViewAllClick: (() -> Unit)? = null,
    viewAllPillSize: CrispyViewAllPillSize = CrispyViewAllPillSize.Default,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val viewAllModifier = if (onViewAllClick == null) {
                Modifier.alpha(0f).clearAndSetSemantics { }
            } else {
                Modifier
            }
            CrispyViewAllPill(
                onClick = onViewAllClick,
                size = viewAllPillSize,
                modifier = viewAllModifier,
            )
        }
        if (showAccent) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .width(80.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun CrispyViewAllPill(
    onClick: (() -> Unit)?,
    size: CrispyViewAllPillSize,
    modifier: Modifier = Modifier,
) {
    val actionSize = if (size == CrispyViewAllPillSize.Compact) 32.dp else 40.dp
    val iconSize = if (size == CrispyViewAllPillSize.Compact) 18.dp else 22.dp
    Box(
        modifier = modifier
            .size(actionSize)
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = "View all",
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(iconSize),
        )
    }
}

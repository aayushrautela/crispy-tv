package com.crispy.tv.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@Composable
fun RailSection(
    title: String,
    items: List<CrispyCardItem>,
    onItemClick: (CrispyCardItem) -> Unit,
    modifier: Modifier = Modifier,
    onItemFocused: (index: Int, item: CrispyCardItem) -> Unit = { _, _ -> },
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 48.dp, vertical = 12.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 48.dp),
        ) {
            itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                CrispyLandscapeCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    onFocus = { onItemFocused(index, item) },
                    modifier = Modifier.width(220.dp),
                )
            }
        }
    }
}

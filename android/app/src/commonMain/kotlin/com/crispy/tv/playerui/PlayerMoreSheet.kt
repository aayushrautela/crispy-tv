package com.crispy.tv.playerui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.details.DetailsPaletteColors
import com.crispy.tv.home.HomeCatalogPosterCard
import com.crispy.tv.ui.resources.Res
import com.crispy.tv.ui.resources.ic_close_filled
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun PlayerMoreSheet(
    visible: Boolean,
    recommendedItems: List<CatalogItem>,
    moreIsLoading: Boolean,
    palette: DetailsPaletteColors,
    onItemSelected: (CatalogItem) -> Unit,
    onClose: () -> Unit,
) {
    val rowPadding = rememberHorizontalCutoutPadding(minPadding = 16.dp)

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
                        .background(Color.Black.copy(alpha = 0.75f))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = onClose,
                        ),
            ) {
                IconButton(
                    onClick = onClose,
                    // The scrim is full-bleed, so an unpadded close button sits in the
                    // screen's corner with no margin at all. Padding after `align`
                    // insets the button inside the corner it was placed in.
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_close_filled),
                        contentDescription = "Close",
                        tint = palette.onPillBackground,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(200)) + slideInVertically(animationSpec = tween(220)) { it },
            exit = fadeOut(animationSpec = tween(180)) + slideOutVertically(animationSpec = tween(180)) { it },
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Explore more",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.onPillBackground,
                    modifier = Modifier.padding(rowPadding),
                )

                val items = recommendedItems
                when {
                    moreIsLoading && items.isEmpty() -> {
                        Text(
                            text = "Loading...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.onPillBackground.copy(alpha = 0.7f),
                            modifier = Modifier.padding(rowPadding),
                        )
                    }

                    items.isEmpty() -> {
                        Text(
                            text = "Nothing here yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.onPillBackground.copy(alpha = 0.7f),
                            modifier = Modifier.padding(rowPadding),
                        )
                    }

                    else -> {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = rowPadding,
                        ) {
                            items(items, key = { "${it.type}:${it.id}" }, contentType = { "poster" }) { item ->
                                HomeCatalogPosterCard(
                                    item = item,
                                    onClick = { onItemSelected(item) },
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

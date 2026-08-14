package com.crispy.tv.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.ui.components.DuotoneGrayContrastColorFilter
import com.crispy.tv.ui.components.duotoneHighlightColor
import com.crispy.tv.ui.components.duotoneShadowColor
import com.crispy.tv.ui.components.rememberCrispyImageModel
import com.crispy.tv.ui.edge_to_edge.crispyRowHuggingPadding

@Composable
internal fun HomeCollectionSectionRow(
    sectionUis: List<HomeCatalogSectionUi>,
    horizontalPadding: Dp,
    onCollectionClick: (CatalogSectionRef) -> Unit,
) {
    val visibleSections by remember(sectionUis) {
        derivedStateOf {
            sectionUis.filter {
                it.isLoading || it.items.isNotEmpty() || it.statusMessage.isNotBlank()
            }
        }
    }

    if (visibleSections.isEmpty()) {
        return
    }

    val sharedSubtitle by remember(visibleSections) {
        derivedStateOf {
            visibleSections
                .map { it.section.subtitle.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .singleOrNull()
                .orEmpty()
        }
    }

    val collectionsLoading = remember(visibleSections) {
        visibleSections.all { it.isLoading && it.items.isEmpty() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HomeRailHeader(
            title = "Collections",
            statusMessage = sharedSubtitle,
            skeleton = collectionsLoading,
            modifier = Modifier.padding(horizontal = horizontalPadding),
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = crispyRowHuggingPadding(horizontalPadding),
        ) {
            items(visibleSections, key = { it.section.key }, contentType = { "collectionCard" }) { sectionUi ->
                HomeCollectionCard(
                    sectionUi = sectionUi,
                    onCollectionClick = { onCollectionClick(sectionUi.section) },
                )
            }
        }
    }
}

@Composable
private fun HomeCollectionCard(
    sectionUi: HomeCatalogSectionUi,
    onCollectionClick: () -> Unit,
) {
    val featured = remember(sectionUi.items) { sectionUi.items.firstOrNull() }
    val backdropModel = rememberCrispyImageModel(
        image = featured?.backdrop,
        width = 640.dp,
        height = 760.dp,
    )
    val highlight = remember(sectionUi.section.key) { duotoneHighlightColor(sectionUi.section.key) }
    val shadow = remember(sectionUi.section.key) { duotoneShadowColor(sectionUi.section.key) }
    val title = remember(sectionUi.section.displayTitle) {
        collectionDisplayTitle(sectionUi.section.displayTitle)
    }

    Box(
        modifier = Modifier
            .width(320.dp)
            .height(380.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(highlight)
            .clickable(onClick = onCollectionClick),
    ) {
        if (backdropModel != null) {
            AsyncImage(
                model = backdropModel,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        colorFilter = DuotoneGrayContrastColorFilter
                        blendMode = BlendMode.Multiply
                    },
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { blendMode = BlendMode.Lighten }
                    .background(shadow),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to Color.Black.copy(alpha = 0.55f),
                            0.6f to Color.Black.copy(alpha = 0.12f),
                            1f to Color.Transparent,
                        ),
                    )
                ),
        )

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title,
                modifier = Modifier.padding(horizontal = 24.dp),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.6f),
                        offset = Offset(0f, 2f),
                        blurRadius = 8f,
                    ),
                ),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun collectionDisplayTitle(title: String): String {
    val trimmedTitle = title.trim()
    val simplifiedTitle = trimmedTitle.replace(Regex("\\s+collection$", RegexOption.IGNORE_CASE), "")
    return simplifiedTitle.ifBlank { trimmedTitle }
}

package com.crispy.tv.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.ui.components.CardStyle
import com.crispy.tv.ui.components.rememberCrispyImageModel
import com.crispy.tv.ui.edge_to_edge.crispyRowHuggingPadding
import kotlin.math.abs
import kotlin.text.RegexOption

private data class PanelColor(val background: Color, val text: Color)

private val COLLECTION_PANELS = listOf(
    PanelColor(Color(0xFF1E3A3A), Color(0xFFE0F0F0)),
    PanelColor(Color(0xFF3A2A4A), Color(0xFFF0E6FF)),
    PanelColor(Color(0xFF4A2E2A), Color(0xFFFFEDE8)),
    PanelColor(Color(0xFF2A3A2E), Color(0xFFECF5EE)),
    PanelColor(Color(0xFF2A2F45), Color(0xFFE8ECFF)),
    PanelColor(Color(0xFF3E3834), Color(0xFFF5EEE8)),
)

private fun panelFor(key: String): PanelColor {
    val index = abs(key.hashCode()) % COLLECTION_PANELS.size
    return COLLECTION_PANELS[index]
}

@Composable
internal fun HomeCollectionSectionRow(
    sectionUis: List<HomeCatalogSectionUi>,
    horizontalPadding: Dp,
    onCollectionClick: (CatalogSectionRef) -> Unit,
) {
    val visibleSections by remember(sectionUis) {
        derivedStateOf {
            sectionUis.filter {
                it.isLoading || it.items.isNotEmpty()
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
            subtitle = sharedSubtitle,
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
        width = 200.dp,
        height = 200.dp,
    )
    val shape = RoundedCornerShape(CardStyle.CardCornerRadiusDp.dp)
    val panel = remember(sectionUi.section.key) { panelFor(sectionUi.section.key) }

    Box(
        modifier = Modifier
            .width(CardStyle.landscapeCardWidth())
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onCollectionClick),
    ) {
        if (backdropModel != null) {
            AsyncImage(
                model = backdropModel,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .fillMaxHeight(),
                contentScale = ContentScale.Crop,
            )
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val midX = size.width / 2f
            val bow = size.width * 0.08f
            val path = Path().apply {
                moveTo(midX, 0f)
                quadraticTo(midX - bow, size.height / 2f, midX, size.height)
                lineTo(size.width, size.height)
                lineTo(size.width, 0f)
                close()
            }
            drawPath(path, panel.background)
        }

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxWidth(0.45f)
                .fillMaxHeight()
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val words = remember(sectionUi.section.displayTitle) {
                collectionDisplayWords(sectionUi.section.displayTitle)
            }
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                words.forEach { word ->
                    Text(
                        text = word,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = panel.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private val COLLECTION_STOPWORDS = setOf("the", "a", "an", "of", "and", "&")

private fun collectionDisplayWords(title: String): List<String> {
    val cleaned = title.replace(Regex("\\s+collection$", RegexOption.IGNORE_CASE), "").trim()
    val split = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
    val filtered = split.filter { it.lowercase() !in COLLECTION_STOPWORDS }
    val source = if (filtered.isNotEmpty()) filtered else split
    return source.take(5)
}

package com.crispy.tv.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.transform.BlurTransformation
import com.crispy.tv.catalog.CatalogSectionRef
import kotlin.text.RegexOption
import com.crispy.tv.ui.components.CardStyle
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
    val context = LocalContext.current
    val blurTransformations = remember {
        listOf(BlurTransformation(context, 3f))
    }
    val backdropModel = rememberCrispyImageModel(
        image = featured?.backdrop,
        width = 200.dp,
        height = 200.dp,
        transformations = blurTransformations,
    )
    val shape = RoundedCornerShape(CardStyle.CardCornerRadiusDp.dp)
    val scrim = remember {
        Brush.radialGradient(
            colorStops = arrayOf(
                0f to Color.Black.copy(alpha = 0.25f),
                0.7f to Color.Black.copy(alpha = 0.30f),
                1f to Color.Black.copy(alpha = 0.45f),
            ),
        )
    }

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
                    .fillMaxSize()
                    .graphicsLayer { scaleX = 1.08f; scaleY = 1.08f },
                contentScale = ContentScale.Crop,
            )
            Box(modifier = Modifier.fillMaxSize().background(scrim))
        }

        CollectionTitle(
            title = sectionUi.section.displayTitle,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun CollectionTitle(title: String, modifier: Modifier = Modifier) {
    val words = remember(title) { collectionDisplayWords(title) }

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val availableWidth = maxWidth
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            words.forEach { word ->
                StretchedWord(word = word, availableWidth = availableWidth)
            }
        }
    }
}

@Composable
private fun StretchedWord(word: String, availableWidth: Dp) {
    val density = LocalDensity.current
    var scaleX by remember(word) { mutableFloatStateOf(1f) }

    BasicText(
        text = word,
        onTextLayout = { result ->
            val natural = result.size.width.toFloat()
            if (natural > 0f) {
                val availablePx = with(density) { availableWidth.roundToPx() }
                val target = (availablePx / natural).coerceIn(1f, 1.6f)
                if (kotlin.math.abs(target - scaleX) > 0.01f) {
                    scaleX = target
                }
            }
        },
        style = TextStyle(
            fontWeight = FontWeight.Black,
            fontSize = 38.sp,
            lineHeight = 35.sp,
            textAlign = TextAlign.Center,
            color = Color(0xFFB4B4B4),
            shadow = Shadow(
                color = Color.Black.copy(alpha = 0.45f),
                offset = Offset(0f, 1f),
                blurRadius = 6f,
            ),
        ),
        modifier = Modifier.graphicsLayer {
            this.scaleX = scaleX
            transformOrigin = TransformOrigin.Center
        },
    )
}

private val COLLECTION_STOPWORDS = setOf("the", "a", "an", "of", "and", "&")

private fun collectionDisplayWords(title: String): List<String> {
    val cleaned = title.replace(Regex("\\s+collection$", RegexOption.IGNORE_CASE), "").trim()
    val split = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
    val filtered = split.filter { it.lowercase() !in COLLECTION_STOPWORDS }
    val source = if (filtered.isNotEmpty()) filtered else split
    return source.take(5)
}

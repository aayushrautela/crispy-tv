package com.crispy.tv.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.crispy.tv.R
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.ui.components.CardStyle
import com.crispy.tv.ui.components.crispyImageRequest
import com.crispy.tv.ui.edge_to_edge.crispyRowHuggingPadding

private const val TOP10_LIMIT = 10
private val Top10PosterWidth: Dp = 150.dp
private val Top10Overlap: Dp = 14.dp
private val Top10FontFamily = FontFamily(Font(R.font.bungee_outline))

@Composable
internal fun HomeTop10SectionRow(
    sectionUi: HomeCatalogSectionUi,
    horizontalPadding: Dp,
    onItemClick: (CatalogItem, String?) -> Unit,
) {
    val items = sectionUi.items
    if (items.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding),
            verticalAlignment = Alignment.Bottom,
        ) {
            Top10HollowMark(text = "TOP10", fontSize = 44.sp, letterSpacing = (-0.02f).sp)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = sectionUi.section.displayTitle.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (0.24f).sp,
                        color = Color.White.copy(alpha = 0.40f),
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (sectionUi.section.subtitle.isNotBlank()) {
                    Text(
                        text = sectionUi.section.subtitle.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (0.24f).sp,
                            color = Color.White.copy(alpha = 0.40f),
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = crispyRowHuggingPadding(horizontalPadding),
        ) {
            itemsIndexed(
                items = items.take(TOP10_LIMIT),
                key = { _, item -> "${item.type}:${item.id}" },
                contentType = { _, _ -> "top10Poster" },
            ) { index, item ->
                HomeTop10Card(
                    item = item,
                    rank = index + 1,
                    onClick = { onItemClick(item, "homecatalog-top10-${sectionUi.section.key}-${item.itemId}") },
                )
            }
        }
    }
}

@Composable
internal fun HomeTop10Card(
    item: CatalogItem,
    rank: Int,
    onClick: () -> Unit,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val rankText = rank.toString()
    val rankStyle = hollowTextStyle(fontSize = 104.sp, letterSpacing = (-0.05f).sp)
    val digitWidthPx = remember(rankText, rankStyle) {
        textMeasurer.measure(AnnotatedString(rankText), style = rankStyle).size.width
    }
    val digitWidth = Dp(digitWidthPx / density.density)
    val posterOffset = (digitWidth - Top10Overlap).coerceAtLeast(0.dp)

    Box(modifier = Modifier.width(Top10PosterWidth + posterOffset)) {
        Text(
            text = rankText,
            style = rankStyle,
            modifier = Modifier.align(Alignment.BottomStart),
        )
        HomeTop10Poster(
            item = item,
            onClick = onClick,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = posterOffset),
        )
    }
}

@Composable
private fun Top10HollowMark(
    text: String,
    fontSize: TextUnit,
    letterSpacing: TextUnit,
) {
    val style = hollowTextStyle(fontSize = fontSize, letterSpacing = letterSpacing)
    Text(text = text, style = style)
}

@Composable
private fun hollowTextStyle(
    fontSize: TextUnit,
    letterSpacing: TextUnit,
): TextStyle = TextStyle(
    fontFamily = Top10FontFamily,
    fontSize = fontSize,
    letterSpacing = letterSpacing,
    color = MaterialTheme.colorScheme.primary,
)

@Composable
private fun HomeTop10Poster(
    item: CatalogItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val posterWidth = Top10PosterWidth
    val posterHeight = (posterWidth.value * 3f / 2f).dp
    val fallbackColor = MaterialTheme.colorScheme.surfaceVariant
    val cardShape = RoundedCornerShape(CardStyle.CardCornerRadiusDp.dp)
    val artworkUrl = item.artwork?.medium ?: item.artworkUrl
    val logoUrl = item.logo?.medium ?: item.logoUrl
    val artworkModel = if (artworkUrl != null) {
        crispyImageRequest(url = artworkUrl, width = posterWidth, height = posterHeight, memoryCacheKey = "top10-${item.itemId}")
    } else {
        null
    }
    val logoModel = if (logoUrl != null) {
        crispyImageRequest(url = logoUrl, width = 112.dp, height = 30.dp, memoryCacheKey = "top10-logo-${item.itemId}")
    } else {
        null
    }
    val scrimBrush = Brush.verticalGradient(
        colorStops = arrayOf(
            0f to Color.Transparent,
            0.55f to Color(0xFF151515).copy(alpha = 0.52f),
            1f to Color(0xFF151515).copy(alpha = 0.88f),
        ),
    )
    val metadataColor = Color.White.copy(alpha = 0.82f)
    val metadataParts = buildList {
        item.year?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
        item.genre?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
        item.rating?.trim()?.takeIf { it.isNotBlank() }?.let { add("\u2605 $it") }
    }

    Box(
        modifier = modifier
            .width(posterWidth)
            .height(posterHeight)
            .clip(cardShape)
            .background(fallbackColor)
            .clickable(onClick = onClick),
    ) {
        if (artworkModel != null) {
            AsyncImage(
                model = artworkModel,
                contentDescription = item.title,
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = item.title.take(1).uppercase(),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.45f)
                .background(scrimBrush),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            if (logoModel != null) {
                AsyncImage(
                    model = logoModel,
                    contentDescription = item.title,
                    modifier = Modifier
                        .fillMaxWidth(0.60f)
                        .height(30.dp),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                )
            } else {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.95f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (metadataParts.isNotEmpty()) {
                Text(
                    text = metadataParts.joinToString(separator = " \u00b7 "),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = metadataColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
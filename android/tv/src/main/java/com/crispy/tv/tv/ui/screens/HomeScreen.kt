@file:OptIn(ExperimentalFoundationApi::class)

package com.crispy.tv.tv.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme as M3MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.home.HomeCatalogSectionUi
import com.crispy.tv.home.HomeCollectionShelfSectionUi
import com.crispy.tv.home.HomeHeroItem
import com.crispy.tv.home.HomeWideRailLayoutUi
import com.crispy.tv.home.HomeWideRailItemUi
import com.crispy.tv.home.HomeWideRailSectionKind
import com.crispy.tv.home.HomeWideRailSectionUi
import com.crispy.tv.home.HomeCatalogRowSectionUi
import com.crispy.tv.home.RailLoadState
import com.crispy.tv.tv.home.TvHomeViewModel
import com.crispy.tv.tv.ui.components.CrispyCardItem
import com.crispy.tv.tv.ui.components.TvCatalogPosterCard
import com.crispy.tv.tv.ui.components.TvCatalogSkeletonCard
import com.crispy.tv.tv.ui.components.TvHeroSection
import com.crispy.tv.tv.ui.components.TvHomeDimensions
import com.crispy.tv.tv.ui.components.TvRailHeader
import com.crispy.tv.tv.ui.components.TvWideRailCard
import com.crispy.tv.tv.ui.components.TvWideRailSkeletonCard
import com.crispy.tv.tv.ui.components.skeletonElement
import kotlinx.coroutines.delay

private const val HERO_SETTLE_DELAY_MS = 140L
private const val WIDE_RAIL_SKELETON_COUNT = 3
private const val CATALOG_SKELETON_COUNT = 5

private data class HeroRef(val railKey: String, val index: Int)

/**
 * TV home mirrors the phone home section pipeline (hero feed, Continue Watching,
 * Up Next, This Week, catalog rows, collection shelves) rendered in the pinned
 * Google TV style: a full-bleed hero backdrop cross-fades with focus while rails
 * scroll in the lower pane.
 */
@Composable
fun HomeScreen(
    viewModel: TvHomeViewModel,
    onOpenItem: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.errorEvents.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(viewModel) {
        viewModel.onHomeVisible()
        onDispose { viewModel.onHomeHidden() }
    }

    var activeRef by remember { mutableStateOf<HeroRef?>(null) }
    var committedRef by remember { mutableStateOf<HeroRef?>(null) }
    var actionsItemKey by remember { mutableStateOf<String?>(null) }

    fun resolveRailCard(ref: HeroRef?): CrispyCardItem? {
        val candidate = ref ?: return null
        val rail = state.wideRailSections[candidate.railKey] ?: return null
        val ready = rail.state as? RailLoadState.Ready ?: return null
        val item = ready.items.getOrNull(candidate.index) ?: return null
        return item.toHeroCard()
    }

    val heroItems = state.heroState.items
    val heroFeedItem = remember(heroItems, state.heroState.selectedId) {
        val selected = state.heroState.selectedId
        heroItems.firstOrNull { it.id == selected } ?: heroItems.firstOrNull()
    }

    val heroCard: CrispyCardItem? = heroFeedItem?.toHeroCard()
        ?: resolveRailCard(committedRef)

    LaunchedEffect(state.wideRailSections) {
        if (heroFeedItem == null && state.wideRailSections.isNotEmpty() && resolveRailCard(committedRef) == null) {
            val firstRail = state.wideRailSections.values.firstOrNull { (it.state as? RailLoadState.Ready)?.items?.isNotEmpty() == true }
            committedRef = firstRail?.let { HeroRef(it.key, 0) }
        }
    }

    LaunchedEffect(activeRef) {
        if (activeRef != null) {
            delay(HERO_SETTLE_DELAY_MS)
            committedRef = activeRef
        }
    }

    val initialLoading = state.heroState.isLoading && state.catalogSections.isEmpty()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenHeight = maxHeight
        val rowsViewportHeight = screenHeight * 0.48f
        val heroHeight = screenHeight - rowsViewportHeight + 28.dp

        Crossfade(
            targetState = heroCard,
            animationSpec = tween(durationMillis = 320),
            label = "home_hero_backdrop",
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .height(heroHeight),
        ) { crossfadedItem ->
            Box(modifier = Modifier.fillMaxSize()) {
                TvHeroSection(item = crossfadedItem, modifier = Modifier.fillMaxSize())
            }
        }

        val listState = rememberLazyListState()
        val density = LocalDensity.current
        val rowHeaderTopInsetPx = with(density) { 0.dp.toPx() }
        val latestCanScrollBackward by rememberUpdatedState(listState.canScrollBackward)
        val rowHeaderSnapSpec = remember(rowHeaderTopInsetPx) {
            object : BringIntoViewSpec {
                override fun calculateScrollDistance(
                    offset: Float,
                    size: Float,
                    containerSize: Float,
                ): Float {
                    val distance = offset - rowHeaderTopInsetPx
                    if (distance < 0f && !latestCanScrollBackward) return 0f
                    return distance
                }
            }
        }

        when {
            initialLoading -> {
                HomeStatusPane(
                    heroHeight = heroHeight,
                    rowsViewportHeight = rowsViewportHeight,
                    message = "Loading…",
                )
            }
            else -> {
                CompositionLocalProvider(LocalBringIntoViewSpec provides rowHeaderSnapSpec) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(rowsViewportHeight)
                            .clipToBounds(),
                        contentPadding = PaddingValues(bottom = rowsViewportHeight),
                        verticalArrangement = Arrangement.spacedBy(TvHomeDimensions.SectionSpacingDp.dp),
                    ) {
                        state.layoutState.blocks.forEach { block ->
                            when (block) {
                                is HomeWideRailLayoutUi -> {
                                    val section = state.wideRailSections[block.key] ?: return@forEach
                                    item(key = block.key, contentType = "wideRail") {
                                        HomeWideRailBlock(
                                            section = section,
                                            actionsItemKey = actionsItemKey,
                                            onToggleActions = { key ->
                                                actionsItemKey = if (actionsItemKey == key) null else key
                                            },
                                            onOpenDetails = { item ->
                                                item.detailsItemId?.let(onOpenItem)
                                            },
                                            onRemove = { item ->
                                                item.continueWatchingItem?.let(viewModel::removeContinueWatchingItem)
                                            },
                                            onItemFocused = { index ->
                                                if (heroFeedItem == null) activeRef = HeroRef(section.key, index)
                                            },
                                        )
                                    }
                                }

                                is HomeCatalogRowSectionUi -> {
                                    val sectionUi = state.catalogSections[block.sectionKey] ?: return@forEach
                                    item(key = block.key, contentType = "catalogSection") {
                                        HomeCatalogSectionBlock(
                                            sectionUi = sectionUi,
                                            onItemClick = { item -> onOpenItem(item.itemId) },
                                        )
                                    }
                                }

                                is HomeCollectionShelfSectionUi -> {
                                    val sectionUis = block.sectionKeys.mapNotNull { state.catalogSections[it] }
                                    if (sectionUis.isNotEmpty()) {
                                        item(key = block.key, contentType = "collectionShelf") {
                                            HomeCollectionShelfBlock(
                                                sectionUis = sectionUis,
                                                onCollectionClick = { },
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (!state.heroState.isLoading && state.layoutState.blocks.isEmpty()) {
                            item(key = "empty") {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "Nothing here yet",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun HomeWideRailItemUi.toHeroCard(): CrispyCardItem =
    CrispyCardItem(
        id = detailsItemId ?: key,
        title = title,
        imageUrl = imageUrl,
        description = subtitle.takeIf { it.isNotBlank() },
        badge = badgeLabel,
    )

private fun HomeHeroItem.toHeroCard(): CrispyCardItem =
    CrispyCardItem(
        id = id,
        title = title,
        imageUrl = artworkUrl,
        rating = rating,
        year = year,
        genre = genres.firstOrNull(),
        description = description,
    )

@Composable
private fun HomeWideRailBlock(
    section: HomeWideRailSectionUi,
    actionsItemKey: String?,
    onToggleActions: (String) -> Unit,
    onOpenDetails: (HomeWideRailItemUi) -> Unit,
    onRemove: (HomeWideRailItemUi) -> Unit,
    onItemFocused: (Int) -> Unit,
) {
    val isLoading = section.state is RailLoadState.Loading
    val readyItems = (section.state as? RailLoadState.Ready)?.items.orEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(TvHomeDimensions.HeaderContentSpacingDp.dp)) {
        TvRailHeader(
            title = section.title,
            skeleton = isLoading,
            modifier = Modifier.padding(horizontal = TvHomeDimensions.EdgePadding),
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = TvHomeDimensions.EdgePadding),
        ) {
            if (isLoading) {
                items(WIDE_RAIL_SKELETON_COUNT, contentType = { "wideSkeleton" }) {
                    TvWideRailSkeletonCard()
                }
            } else {
                itemsIndexed(
                    readyItems,
                    key = { _, item -> item.key },
                    contentType = { _, _ -> "wideRailCard" },
                ) { index, item ->
                    val canRemove = section.kind == HomeWideRailSectionKind.CONTINUE_WATCHING &&
                        item.continueWatchingItem != null
                    TvWideRailCard(
                        item = item,
                        onClick = { item.detailsItemId?.let { onOpenDetails(item) } },
                        onLongClick = if (canRemove) {
                            { onToggleActions(item.key) }
                        } else {
                            null
                        },
                        onFocused = { onItemFocused(index) },
                    )
                }
            }
        }
    }

    val currentActionsKey = actionsItemKey ?: return
    val actionItem = readyItems.firstOrNull { it.key == currentActionsKey } ?: return
    TvWideRailItemActionsDialog(
        item = actionItem,
        showRemove = section.kind == HomeWideRailSectionKind.CONTINUE_WATCHING &&
            actionItem.continueWatchingItem != null,
        onDismiss = { onToggleActions(currentActionsKey) },
        onOpenDetails = {
            onToggleActions(currentActionsKey)
            onOpenDetails(actionItem)
        },
        onRemove = {
            onToggleActions(currentActionsKey)
            onRemove(actionItem)
        },
    )
}

@Composable
private fun TvWideRailItemActionsDialog(
    item: HomeWideRailItemUi,
    showRemove: Boolean,
    onDismiss: () -> Unit,
    onOpenDetails: () -> Unit,
    onRemove: () -> Unit,
) {
    M3MaterialTheme(colorScheme = darkColorScheme()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(text = item.title) },
            text = {
                Text(
                    text = listOfNotNull(
                        item.subtitle.takeIf { it.isNotBlank() },
                        item.badgeLabel,
                    ).joinToString(" · ").ifBlank { item.title },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                Button(onClick = onOpenDetails) { Text(text = "Open details") }
            },
            dismissButton = {
                if (showRemove) {
                    TextButton(onClick = onRemove) { Text(text = "Remove") }
                } else {
                    TextButton(onClick = onDismiss) { Text(text = "Cancel") }
                }
            },
        )
    }
}

@Composable
private fun HomeCatalogSectionBlock(
    sectionUi: HomeCatalogSectionUi,
    onItemClick: (CatalogItem) -> Unit,
) {
    val sectionSkeleton = sectionUi.isLoading && sectionUi.items.isEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(TvHomeDimensions.HeaderContentSpacingDp.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TvHomeDimensions.EdgePadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (sectionSkeleton) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.35f)
                            .height(22.dp)
                            .skeletonElement(shape = RoundedCornerShape(4.dp), pulse = false),
                    )
                } else {
                    Text(
                        text = sectionUi.section.displayTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (sectionUi.section.subtitle.isNotBlank()) {
                        Text(
                            text = sectionUi.section.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = TvHomeDimensions.EdgePadding),
        ) {
            if (sectionSkeleton) {
                items(CATALOG_SKELETON_COUNT, contentType = { "catalogSkeleton" }) {
                    TvCatalogSkeletonCard()
                }
            } else {
                items(
                    sectionUi.items,
                    key = { "${it.type}:${it.id}" },
                    contentType = { "catalogPoster" },
                ) { item ->
                    TvCatalogPosterCard(
                        item = item,
                        onClick = { onItemClick(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeCollectionShelfBlock(
    sectionUis: List<HomeCatalogSectionUi>,
    onCollectionClick: (CatalogSectionRef) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(TvHomeDimensions.HeaderContentSpacingDp.dp)) {
        TvRailHeader(
            title = "Collections",
            modifier = Modifier.padding(horizontal = TvHomeDimensions.EdgePadding),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = TvHomeDimensions.EdgePadding),
        ) {
            items(sectionUis, key = { it.section.key }, contentType = { "collectionCard" }) { sectionUi ->
                TvCollectionCard(
                    title = sectionUi.section.displayTitle,
                    artworkUrl = sectionUi.items.firstOrNull()?.artworkUrl,
                    onClick = { onCollectionClick(sectionUi.section) },
                )
            }
        }
    }
}

@Composable
private fun TvCollectionCard(
    title: String,
    artworkUrl: String?,
    onClick: () -> Unit,
) {
    val cardShape = RoundedCornerShape(TvHomeDimensions.CatalogCardCornerRadiusDp.dp)
    Box(
        modifier = Modifier
            .width(TvHomeDimensions.CatalogCardWidth)
            .height(TvHomeDimensions.CatalogCardWidth)
            .clip(cardShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.BottomStart,
    ) {
        if (artworkUrl != null) {
            AsyncImage(
                model = artworkUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.7f),
                        ),
                    ),
                ),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun HomeStatusPane(
    heroHeight: Dp,
    rowsViewportHeight: Dp,
    message: String,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(heroHeight),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(rowsViewportHeight),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

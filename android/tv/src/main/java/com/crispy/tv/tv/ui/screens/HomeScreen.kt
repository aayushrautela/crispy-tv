@file:OptIn(ExperimentalFoundationApi::class)

package com.crispy.tv.tv.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme as M3MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
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
import com.crispy.tv.tv.ui.components.LocalTvContentFocusRequester
import com.crispy.tv.tv.ui.components.TvCatalogPosterCard
import com.crispy.tv.tv.ui.components.TvCatalogSkeletonCard
import com.crispy.tv.tv.ui.components.TvHeroSection
import com.crispy.tv.tv.ui.components.TvHomeDimensions
import com.crispy.tv.tv.ui.components.TvRailHeader
import com.crispy.tv.tv.ui.components.TvWideRailCard
import com.crispy.tv.tv.ui.components.TvWideRailSkeletonCard
import com.crispy.tv.tv.ui.components.skeletonElement
import com.crispy.tv.tv.ui.components.tvRailBringIntoViewSpec
import com.crispy.tv.tv.ui.components.tvRowsBringIntoViewSpec
import kotlinx.coroutines.delay

private const val HERO_SETTLE_DELAY_MS = 140L
private const val WIDE_RAIL_SKELETON_COUNT = 3
private const val CATALOG_SKELETON_COUNT = 5
private val RowsFocusTopInsetDp = 40.dp

/**
 * TV home mirrors the phone home section pipeline (hero feed, Continue Watching,
 * Up Next, This Week, catalog rows, collection shelves) in the Nuvio style: the
 * top half is a live detail panel (backdrop, title, synopsis, progress, actions)
 * for the currently focused title, with rails scrolling in the lower pane.
 */
@Composable
fun HomeScreen(
    viewModel: TvHomeViewModel,
    onOpenItem: (String) -> Unit,
    onPlayItem: (String) -> Unit,
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

    var activePreview by remember { mutableStateOf<CrispyCardItem?>(null) }
    var committedPreview by remember { mutableStateOf<CrispyCardItem?>(null) }
    var actionsItemKey by remember { mutableStateOf<String?>(null) }

    val heroItems = state.heroState.items
    val heroFeedItem = remember(heroItems, state.heroState.selectedId) {
        val selected = state.heroState.selectedId
        heroItems.firstOrNull { it.id == selected } ?: heroItems.firstOrNull()
    }

    val previewCard: CrispyCardItem? = committedPreview ?: heroFeedItem?.toHeroCard()

    LaunchedEffect(activePreview) {
        if (activePreview != null) {
            delay(HERO_SETTLE_DELAY_MS)
            committedPreview = activePreview
        }
    }

    val initialLoading = state.heroState.isLoading && state.catalogSections.isEmpty()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenHeight = maxHeight
        val rowsViewportHeight = screenHeight * 0.52f
        val heroHeight = screenHeight - rowsViewportHeight + 36.dp

        val rowsListState = rememberLazyListState()
        val density = LocalDensity.current
        val rowsTopInsetPx = remember(density) { with(density) { RowsFocusTopInsetDp.roundToPx() } }
        val rowsScrollSpec = remember(rowsTopInsetPx) {
            tvRowsBringIntoViewSpec(
                topInsetPx = rowsTopInsetPx,
                canScrollBackward = { rowsListState.canScrollBackward },
            )
        }
        val contentFocusRequester = LocalTvContentFocusRequester.current

        Crossfade(
            targetState = previewCard,
            animationSpec = tween(durationMillis = 320),
            label = "home_hero_panel",
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .height(heroHeight),
        ) { card ->
            Box(modifier = Modifier.fillMaxSize()) {
                TvHeroSection(item = card, modifier = Modifier.fillMaxSize())
                if (card != null) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 48.dp, bottom = 44.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = { onOpenItem(card.id) }) { Text(text = "Details") }
                        Button(onClick = { onPlayItem(card.id) }) { Text(text = "Play") }
                    }
                    card.progressFraction?.let { progress ->
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 48.dp, bottom = 28.dp)
                                .width(220.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progress)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    }
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
                CompositionLocalProvider(LocalBringIntoViewSpec provides rowsScrollSpec) {
                    LazyColumn(
                        state = rowsListState,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(rowsViewportHeight)
                            .clipToBounds()
                            .then(contentFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
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
                                            onItemPreview = { item ->
                                                activePreview = item.toHeroCard()
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
                                            onItemPreview = { item -> activePreview = item.toHeroCard() },
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
        progressFraction = progressFraction,
    )

private fun CatalogItem.toHeroCard(): CrispyCardItem =
    CrispyCardItem(
        id = itemId,
        title = title,
        imageUrl = artworkUrl,
        rating = rating,
        year = year,
        genre = genre,
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

/**
 * Horizontal rail with the shared focus scroll spec: the focused card's leading
 * edge settles at the edge padding instead of the default minimal scroll, so
 * cards never end up half-scrolled under the previous rail.
 */
@Composable
private fun TvHomeRailRow(
    horizontalArrangement: Arrangement.Horizontal,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    val density = LocalDensity.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val startInsetPx = remember(density) { with(density) { TvHomeDimensions.EdgePadding.roundToPx() } }
    val railScrollSpec = remember(startInsetPx, isRtl) {
        tvRailBringIntoViewSpec(startInsetPx = startInsetPx, isRtl = isRtl)
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides railScrollSpec) {
        LazyRow(
            modifier = modifier,
            horizontalArrangement = horizontalArrangement,
            contentPadding = contentPadding,
            content = content,
        )
    }
}

@Composable
private fun HomeWideRailBlock(
    section: HomeWideRailSectionUi,
    actionsItemKey: String?,
    onToggleActions: (String) -> Unit,
    onOpenDetails: (HomeWideRailItemUi) -> Unit,
    onRemove: (HomeWideRailItemUi) -> Unit,
    onItemPreview: (HomeWideRailItemUi) -> Unit,
) {
    val isLoading = section.state is RailLoadState.Loading
    val readyItems = (section.state as? RailLoadState.Ready)?.items.orEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(TvHomeDimensions.HeaderContentSpacingDp.dp)) {
        TvRailHeader(
            title = section.title,
            skeleton = isLoading,
            modifier = Modifier.padding(horizontal = TvHomeDimensions.EdgePadding),
        )

        TvHomeRailRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = TvHomeDimensions.EdgePadding),
        ) {
            if (isLoading) {
                items(WIDE_RAIL_SKELETON_COUNT, contentType = { "wideSkeleton" }) {
                    TvWideRailSkeletonCard()
                }
            } else {
                items(
                    readyItems,
                    key = { item -> item.key },
                    contentType = { "wideRailCard" },
                ) { item ->
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
                        onFocused = { onItemPreview(item) },
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
    onItemPreview: (CatalogItem) -> Unit,
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

        TvHomeRailRow(
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
                        onFocused = { onItemPreview(item) },
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
        TvHomeRailRow(
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

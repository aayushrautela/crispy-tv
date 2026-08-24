package com.crispy.tv.tv.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.crispy.tv.tv.home.HomeViewModel
import com.crispy.tv.tv.ui.components.CrispyCardItem
import com.crispy.tv.tv.ui.components.RailSection
import com.crispy.tv.tv.ui.components.TvHeroSection
import kotlinx.coroutines.delay

private const val HERO_SETTLE_DELAY_MS = 140L

private data class HeroRef(val railKey: String, val index: Int)

/**
 * Google TV / Nuvio-style home: a pinned hero backdrop owns the upper region and
 * cross-fades as focus moves through cards; rails live in an independent lower pane
 * whose focused row snaps to the top edge under the hero.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenItem: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var activeRef by remember { mutableStateOf<HeroRef?>(null) }
    var committedRef by remember { mutableStateOf<HeroRef?>(null) }

    fun resolveItem(ref: HeroRef?): CrispyCardItem? =
        ref?.let { candidate ->
            state.rails.firstOrNull { it.key == candidate.railKey }
                ?.items?.getOrNull(candidate.index)
        }

    LaunchedEffect(state.rails) {
        if (state.rails.isNotEmpty() && resolveItem(committedRef) == null) {
            val first = state.rails.first()
            committedRef = HeroRef(first.key, 0)
        }
        if (state.rails.isEmpty()) {
            activeRef = null
            committedRef = null
        }
    }

    LaunchedEffect(activeRef) {
        if (activeRef != null) {
            delay(HERO_SETTLE_DELAY_MS)
            committedRef = activeRef
        }
    }

    val heroItem = resolveItem(committedRef)
        ?: state.rails.firstOrNull()?.items?.firstOrNull()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenHeight = maxHeight
        val rowsViewportHeight = screenHeight * 0.48f
        val heroHeight = screenHeight - rowsViewportHeight + 28.dp

        Crossfade(
            targetState = heroItem,
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
            state.loading && state.rails.isEmpty() -> {
                HeroStatusPane(
                    heroHeight = heroHeight,
                    rowsViewportHeight = rowsViewportHeight,
                    message = "Loading…",
                )
            }
            state.error != null && state.rails.isEmpty() -> {
                HeroStatusPane(
                    heroHeight = heroHeight,
                    rowsViewportHeight = rowsViewportHeight,
                    message = state.error ?: "Something went wrong",
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
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        itemsIndexed(state.rails, key = { _, rail -> rail.key }) { _, rail ->
                            RailSection(
                                title = rail.title,
                                items = rail.items,
                                onItemClick = { item -> onOpenItem(item.id) },
                                onItemFocused = { index, _ ->
                                    activeRef = HeroRef(rail.key, index)
                                },
                            )
                        }
                        if (!state.loading && state.rails.isEmpty()) {
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

@Composable
private fun HeroStatusPane(
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

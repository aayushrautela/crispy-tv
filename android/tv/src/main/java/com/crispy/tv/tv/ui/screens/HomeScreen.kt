package com.crispy.tv.tv.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.crispy.tv.tv.home.HomeViewModel
import com.crispy.tv.tv.ui.components.CrispyCardItem
import com.crispy.tv.tv.ui.components.RailSection
import com.crispy.tv.tv.ui.components.TvHeroSection

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenItem: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var heroItem by remember { mutableStateOf<CrispyCardItem?>(null) }
    val scroll = rememberScrollState()

    LaunchedEffect(Unit) { viewModel.refresh() }

    // default hero = first item of the first rail until the user focuses something
    LaunchedEffect(state.rails) {
        if (heroItem == null) {
            heroItem = state.rails.firstOrNull()?.items?.firstOrNull()
        }
    }

    when {
        state.loading && state.rails.isEmpty() -> {
            Column(modifier = modifier.fillMaxSize()) {
                TvHeroSection(item = null)
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Loading…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
        state.error != null && state.rails.isEmpty() -> {
            Column(modifier = modifier.fillMaxSize()) {
                TvHeroSection(item = null)
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = state.error ?: "Something went wrong",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
        else -> {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .verticalScroll(scroll),
            ) {
                TvHeroSection(item = heroItem)
                Spacer(modifier = Modifier.height(8.dp))
                state.rails.forEach { rail ->
                    RailSection(
                        title = rail.title,
                        items = rail.items,
                        onItemClick = { item -> onOpenItem(item.id) },
                        onItemFocused = { item ->
                            if (item.imageUrl != null || item.logoUrl != null) {
                                heroItem = item
                            }
                        },
                    )
                }
                if (!state.loading && state.rails.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Nothing here yet",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

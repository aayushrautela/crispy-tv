package com.crispy.rewrite.discover

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crispy.rewrite.BuildConfig
import com.crispy.rewrite.catalog.CatalogItem
import com.crispy.rewrite.catalog.CatalogSectionRef
import com.crispy.rewrite.home.HomeCatalogService
import com.crispy.rewrite.ui.components.PosterCard
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DiscoverTypeFilter(val label: String, val mediaType: String?) {
    All(label = "All", mediaType = null),
    Movies(label = "Movies", mediaType = "movie"),
    Series(label = "Series", mediaType = "series")
}

data class DiscoverSectionUi(
    val section: CatalogSectionRef,
    val items: List<CatalogItem> = emptyList(),
    val isLoading: Boolean = true,
    val statusMessage: String = ""
)

data class DiscoverUiState(
    val typeFilter: DiscoverTypeFilter = DiscoverTypeFilter.All,
    val isRefreshing: Boolean = false,
    val statusMessage: String = "",
    val sections: List<DiscoverSectionUi> = emptyList()
)

class DiscoverViewModel(
    private val homeCatalogService: HomeCatalogService
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState

    private var refreshJob: Job? = null

    init {
        refresh()
    }

    fun setTypeFilter(filter: DiscoverTypeFilter) {
        _uiState.update { it.copy(typeFilter = filter) }
        refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob =
            viewModelScope.launch {
                val filterSnapshot = uiState.value.typeFilter
                _uiState.update { it.copy(isRefreshing = true, sections = emptyList(), statusMessage = "Loading catalogs...") }

                val (sections, statusMessage) = homeCatalogService.listHomeCatalogSections(limit = 25)
                val filtered =
                    sections.filter { section ->
                        filterSnapshot.mediaType == null ||
                            section.mediaType.equals(filterSnapshot.mediaType, ignoreCase = true)
                    }

                _uiState.update {
                    it.copy(
                        statusMessage = statusMessage,
                        sections = filtered.map { section -> DiscoverSectionUi(section = section, isLoading = true) }
                    )
                }

                for (section in filtered) {
                    val result = runCatching {
                        homeCatalogService.fetchCatalogPage(section = section, page = 1, pageSize = 12)
                    }
                    _uiState.update { current ->
                        current.copy(
                            sections =
                                current.sections.map { existing ->
                                    if (existing.section.key != section.key) {
                                        existing
                                    } else {
                                        val pageResult = result.getOrNull()
                                        existing.copy(
                                            isLoading = false,
                                            items = pageResult?.items.orEmpty(),
                                            statusMessage =
                                                pageResult?.statusMessage
                                                    ?: (result.exceptionOrNull()?.message ?: "Failed to load")
                                        )
                                    }
                                }
                        )
                    }
                }

                _uiState.update { it.copy(isRefreshing = false) }
            }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(DiscoverViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return DiscoverViewModel(
                            homeCatalogService =
                                HomeCatalogService(
                                    context = appContext,
                                    addonManifestUrlsCsv = BuildConfig.METADATA_ADDON_URLS
                                )
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }
}

@Composable
fun DiscoverRoute(
    onNavigateToSearch: () -> Unit,
    onItemClick: (CatalogItem) -> Unit,
    onSeeAllClick: (CatalogSectionRef) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val viewModel: DiscoverViewModel =
        viewModel(
            factory = remember(appContext) {
                DiscoverViewModel.factory(appContext)
            }
        )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    DiscoverScreen(
        uiState = uiState,
        onRefresh = viewModel::refresh,
        onTypeFilterClick = viewModel::setTypeFilter,
        onNavigateToSearch = onNavigateToSearch,
        onItemClick = onItemClick,
        onSeeAllClick = onSeeAllClick
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoverScreen(
    uiState: DiscoverUiState,
    onRefresh: () -> Unit,
    onTypeFilterClick: (DiscoverTypeFilter) -> Unit,
    onNavigateToSearch: () -> Unit,
    onItemClick: (CatalogItem) -> Unit,
    onSeeAllClick: (CatalogSectionRef) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Discover") },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(imageVector = Icons.Outlined.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onNavigateToSearch
                ) {
                    ListItem(
                        headlineContent = { Text("Search") },
                        supportingContent = { Text("Find movies and series") },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = null
                            )
                        }
                    )
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DiscoverTypeFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = uiState.typeFilter == filter,
                            onClick = { onTypeFilterClick(filter) },
                            label = { Text(filter.label) }
                        )
                    }
                }
            }

            if (uiState.statusMessage.isNotBlank()) {
                item {
                    Text(
                        text = uiState.statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(uiState.sections, key = { it.section.key }) { sectionUi ->
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = sectionUi.section.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { onSeeAllClick(sectionUi.section) }) {
                            Text("See all")
                        }
                    }

                    when {
                        sectionUi.isLoading && sectionUi.items.isEmpty() -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(
                                    text = if (sectionUi.statusMessage.isNotBlank()) sectionUi.statusMessage else "Loading...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        sectionUi.items.isEmpty() -> {
                            Text(
                                text = if (sectionUi.statusMessage.isNotBlank()) sectionUi.statusMessage else "Nothing to show",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        else -> {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(end = 8.dp)
                            ) {
                                items(sectionUi.items, key = { it.id }) { item ->
                                    PosterCard(
                                        title = item.title,
                                        posterUrl = item.posterUrl,
                                        backdropUrl = item.backdropUrl,
                                        rating = item.rating,
                                        modifier = Modifier.width(124.dp),
                                        onClick = { onItemClick(item) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (uiState.sections.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (uiState.isRefreshing) "Loading..." else "No catalogs available",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

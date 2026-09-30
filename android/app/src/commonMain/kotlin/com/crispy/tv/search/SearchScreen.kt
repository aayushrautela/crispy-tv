package com.crispy.tv.search

import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crispy.tv.accounts.ActiveProfileInfo
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.ui.components.CardStyle
import com.crispy.tv.ui.components.CrispyShelfSection
import com.crispy.tv.ui.components.LandscapeCard
import com.crispy.tv.ui.components.PersonCircleCard
import com.crispy.tv.ui.components.PersonProfileSharedKeys
import com.crispy.tv.ui.components.skeletonElement
import com.crispy.tv.ui.edge_to_edge.safeBottomPadding
import com.crispy.tv.ui.resources.Res
import com.crispy.tv.ui.resources.ic_close
import com.crispy.tv.ui.resources.ic_history
import com.crispy.tv.ui.theme.CrispyPalette
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.painterResource

/**
 * The identity Compose gives a recent-search chip and a suggestion row.
 *
 * Both rows used to spell this as a lambda inline, `key = { it.lowercase() }`, and that
 * expression used to be `it.lowercase(Locale.ROOT)`. Deleting an explicit `Locale.ROOT`
 * is exact rather than approximate -- Kotlin's no-arg `String.lowercase()` is specified
 * locale-invariant, and the argument-taking overload is not -- so the two are the same
 * function by definition. That is why `java.util.Locale` could be deleted rather than
 * moved.
 *
 * **It is a named `internal` function and not an inline lambda because an inline lambda
 * cannot be tested.** It is an argument to `items(...)` inside a `LazyRow`, so no test can
 * invoke it; the suite could only re-spell `lowercase()` and prove the standard library,
 * which is not evidence about this code. A mutation that made the key case-sensitive
 * survived that suite and was reported as untested behaviour, when it is very much
 * reachable behaviour: without the fold, `"Trakt"` and `"trakt"` become two distinct rows
 * and the list loses its identity when the user edits the case of what they typed. Naming
 * it makes the same rule that lifted the rating badge's builders out of `private` apply
 * here -- a decision no test can call is a decision no test can cover. See
 * `SearchScreenTest`, which calls this function rather than a helper of its own.
 */
internal fun searchItemKey(query: String): String = query.lowercase()

@Composable
fun SearchRoute(
    onItemClick: (CatalogItem, String?) -> Unit,
    onOpenAccountsProfiles: () -> Unit,
    scrollToTopRequests: StateFlow<Int>,
    onScrollToTopConsumed: () -> Unit,
    // Both are values rather than composable slots: `viewModel(factory = ...)` keys on
    // factory identity, so a slot would be re-invoked every recomposition and defeat the
    // `remember` that keeps the store alive. No defaults -- a commonMain route cannot take
    // a Context, so the androidMain nav graph builds both and passes them down.
    viewModelFactory: ViewModelProvider.Factory,
    loadProfile: suspend () -> ActiveProfileInfo?,
) {
    val viewModel = rememberSearchViewModel(viewModelFactory = viewModelFactory)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val browseGridState = rememberLazyGridState()
    val resultsListState = rememberLazyListState()
    val pageHorizontalPadding = responsivePageHorizontalPadding()
    val scrollToTopRequest by scrollToTopRequests.collectAsStateWithLifecycle()

    LaunchedEffect(scrollToTopRequest, uiState.hasActiveResults) {
        if (scrollToTopRequest > 0) {
            if (uiState.hasActiveResults) {
                resultsListState.animateScrollToItem(0)
            } else {
                browseGridState.animateScrollToItem(0)
            }
            onScrollToTopConsumed()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SearchTopBar(
                onOpenAccountsProfiles = onOpenAccountsProfiles,
                loadProfile = loadProfile,
            )
        },
    ) { paddingValues ->
        val contentModifier =
            Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
                .imePadding()

        SearchContent(
            uiState = uiState,
            browseGridState = browseGridState,
            resultsListState = resultsListState,
            pageHorizontalPadding = pageHorizontalPadding,
            onQueryChange = viewModel::updateQuery,
            onSearch = viewModel::submitSearch,
            onAiSearch = viewModel::submitAiSearch,
            onClear = viewModel::clearSearch,
            onGenreClick = viewModel::selectGenre,
            onRecentSearchClick = viewModel::submitSearch,
            onRemoveRecentSearch = viewModel::removeRecentSearch,
            onSuggestionClick = viewModel::submitSearch,
            onItemClick = onItemClick,
            modifier = contentModifier,
        )
    }
}

@Composable
private fun SearchContent(
    uiState: SearchUiState,
    browseGridState: LazyGridState,
    resultsListState: LazyListState,
    pageHorizontalPadding: Dp,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onAiSearch: () -> Unit,
    onClear: () -> Unit,
    onGenreClick: (SearchGenreSuggestion) -> Unit,
    onRecentSearchClick: (String) -> Unit,
    onRemoveRecentSearch: (String) -> Unit,
    onSuggestionClick: (String) -> Unit,
    onItemClick: (CatalogItem, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isAiMode = uiState.searchMode == SearchMode.AI
    val isAiLoading = isAiMode && uiState.isLoading

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = pageHorizontalPadding, vertical = Dimensions.SmallSpacing),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchBar(
                query = uiState.query,
                onQueryChange = onQueryChange,
                onSearch = onSearch,
                onClear = onClear,
                isAiLoading = isAiLoading,
                modifier = Modifier.weight(1f),
            )
            AiSearchButton(
                onClick = onAiSearch,
                isHighlighted = isAiMode && uiState.hasActiveResults || isAiLoading,
            )
        }

        when {
            uiState.hasActiveResults -> SearchResultsContent(
                uiState = uiState,
                listState = resultsListState,
                pageHorizontalPadding = pageHorizontalPadding,
                onItemClick = onItemClick,
                emptyMessage = uiState.statusMessage,
                modifier = Modifier.fillMaxSize(),
            )

            else -> SearchBrowseContent(
                uiState = uiState,
                gridState = browseGridState,
                pageHorizontalPadding = pageHorizontalPadding,
                onGenreClick = onGenreClick,
                onRecentSearchClick = onRecentSearchClick,
                onRemoveRecentSearch = onRemoveRecentSearch,
                onSuggestionClick = onSuggestionClick,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun SearchBrowseContent(
    uiState: SearchUiState,
    gridState: LazyGridState,
    pageHorizontalPadding: Dp,
    onGenreClick: (SearchGenreSuggestion) -> Unit,
    onRecentSearchClick: (String) -> Unit,
    onRemoveRecentSearch: (String) -> Unit,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SearchGrid(
        state = gridState,
        columns = GridCells.Fixed(2),
        pageHorizontalPadding = pageHorizontalPadding,
        modifier = modifier,
    ) {
        if (uiState.suggestions.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SuggestionStrip(
                    suggestions = uiState.suggestions,
                    onSuggestionClick = onSuggestionClick,
                )
            }
        }
        if (uiState.recentSearches.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                RecentSearchStrip(
                    recentSearches = uiState.recentSearches,
                    onRecentSearchClick = onRecentSearchClick,
                    onRemoveRecentSearch = onRemoveRecentSearch,
                )
            }
        }
        gridItems(
            items = SearchGenreSuggestion.entries,
            key = { it.name },
        ) { genre ->
            GenreTab(
                genre = genre,
                onClick = { onGenreClick(genre) },
            )
        }
    }
}

@Composable
private fun RecentSearchStrip(
    recentSearches: List<String>,
    onRecentSearchClick: (String) -> Unit,
    onRemoveRecentSearch: (String) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(
            items = recentSearches,
            // `lowercase(Locale.ROOT)` -> `lowercase()` is exact, not an approximation: the
            // no-arg form is the locale-invariant one. Unlike the `Locale.US` call sites
            // in DetailsUseCases and WatchCtaResolver, nothing here relies on the
            // argument being ASCII.
            key = { searchItemKey(it) },
        ) { query ->
            RecentSearchChip(
                query = query,
                onClick = { onRecentSearchClick(query) },
                onRemoveClick = { onRemoveRecentSearch(query) },
            )
        }
    }
}

@Composable
private fun SuggestionStrip(
    suggestions: List<String>,
    onSuggestionClick: (String) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(
            items = suggestions,
            // `lowercase(Locale.ROOT)` -> `lowercase()` is exact, not an approximation: the
            // no-arg form is the locale-invariant one. Unlike the `Locale.US` call sites
            // in DetailsUseCases and WatchCtaResolver, nothing here relies on the
            // argument being ASCII.
            key = { searchItemKey(it) },
        ) { suggestion ->
            SuggestionChip(
                suggestion = suggestion,
                onClick = { onSuggestionClick(suggestion) },
            )
        }
    }
}

@Composable
private fun SuggestionChip(
    suggestion: String,
    onClick: () -> Unit,
) {
    Text(
        text = suggestion,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun RecentSearchChip(
    query: String,
    onClick: () -> Unit,
    onRemoveClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(Res.drawable.ic_history),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = query,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onRemoveClick)
                .padding(2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_close),
                contentDescription = "Remove recent search",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SearchResultsContent(
    uiState: SearchUiState,
    listState: LazyListState,
    pageHorizontalPadding: Dp,
    onItemClick: (CatalogItem, String?) -> Unit,
    emptyMessage: String?,
    modifier: Modifier = Modifier,
) {
    val buckets = uiState.resultBuckets
    val isLoading = uiState.isLoading

    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(
            start = pageHorizontalPadding,
            end = pageHorizontalPadding,
            bottom = safeBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        when {
            isLoading && buckets.isEmpty -> {
                item { SearchSectionSkeleton() }
                item { SearchSectionSkeleton() }
                item { SearchSectionSkeleton() }
            }

            buckets.isEmpty -> {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = emptyMessage
                                ?: if (uiState.searchMode == SearchMode.AI) "No AI matches found." else "No results",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            else -> {
                if (isLoading) {
                    item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = CrispyPalette.spinner) }
                }
                if (buckets.movies.isNotEmpty()) {
                    item(key = "movies") {
                        SearchSectionRow(title = "Movies", items = buckets.movies, onItemClick = onItemClick)
                    }
                }
                if (buckets.series.isNotEmpty()) {
                    item(key = "series") {
                        SearchSectionRow(title = "Series", items = buckets.series, onItemClick = onItemClick)
                    }
                }
                if (buckets.people.isNotEmpty()) {
                    item(key = "people") {
                        SearchPeopleRow(items = buckets.people, onItemClick = onItemClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSectionRow(
    title: String,
    items: List<CatalogItem>,
    onItemClick: (CatalogItem, String?) -> Unit,
) {
    CrispyShelfSection(
        title = title,
        entries = items,
        itemSpacing = 12.dp,
        key = { "${it.type}:${it.id}" },
        itemContent = { item ->
            val sharedElementKey = "search-${title}-${item.itemId}"
            LandscapeCard(
                title = item.title,
                artworkUrl = item.artworkUrl,
                artwork = item.artwork,
                logoUrl = item.logoUrl,
                logo = item.logo,
                rating = item.rating,
                year = item.year,
                genre = item.genre,
                modifier = Modifier.width(CardStyle.landscapeCardWidth()),
                onClick = { onItemClick(item, sharedElementKey) },
                itemId = item.itemId,
                sharedElementKey = sharedElementKey,
            )
        },
    )
}

@Composable
private fun SearchPeopleRow(
    items: List<CatalogItem>,
    onItemClick: (CatalogItem, String?) -> Unit,
) {
    CrispyShelfSection(
        title = "People",
        entries = items,
        itemSpacing = 12.dp,
        key = { "${it.type}:${it.id}" },
        itemContent = { item ->
            val sharedElementKey = PersonProfileSharedKeys.forPerson(item.itemId)
            PersonCircleCard(
                name = item.title,
                profileUrl = item.artworkUrl,
                onClick = { onItemClick(item, sharedElementKey) },
                sharedElementKey = sharedElementKey,
                subtitle = item.genre,
            )
        },
    )
}

@Composable
private fun SearchSectionSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .width(120.dp)
                .height(24.dp)
                .skeletonElement(pulse = false),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(6, contentType = { "posterSkeleton" }) {
                Box(
                    modifier = Modifier
                        .width(CardStyle.landscapeCardWidth())
                        .aspectRatio(CardStyle.LandscapeAspectRatio)
                        .skeletonElement(pulse = false),
                )
            }
        }
    }
}

@Composable
private fun GenreTab(
    genre: SearchGenreSuggestion,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Image(
            painter = painterResource(genre.imageResId),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x1A000000)),
        )
        Text(
            text = genre.label,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 12.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SearchGrid(
    state: LazyGridState,
    columns: GridCells,
    pageHorizontalPadding: Dp,
    modifier: Modifier = Modifier,
    content: LazyGridScope.() -> Unit,
) {
    LazyVerticalGrid(
        state = state,
        columns = columns,
        modifier = modifier,
        contentPadding = PaddingValues(
            start = pageHorizontalPadding,
            top = Dimensions.SmallSpacing,
            end = pageHorizontalPadding,
            bottom = safeBottomPadding(),
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}





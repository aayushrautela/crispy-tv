package com.crispy.rewrite.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crispy.rewrite.catalog.CatalogItem
import com.crispy.rewrite.catalog.CatalogSectionRef
import com.crispy.rewrite.ui.theme.Dimensions
import com.crispy.rewrite.ui.theme.responsivePageHorizontalPadding

@Composable
internal fun HomeScreen(
    onHeroClick: (HomeHeroItem) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onCatalogItemClick: (CatalogItem) -> Unit,
    onCatalogSeeAllClick: (CatalogSectionRef) -> Unit
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val viewModel: HomeViewModel = viewModel(
        factory = remember(appContext) {
            HomeViewModel.factory(appContext)
        }
    )
    val heroState by viewModel.heroState.collectAsStateWithLifecycle()
    val continueWatchingState by viewModel.continueWatchingState.collectAsStateWithLifecycle()
    val upNextState by viewModel.upNextState.collectAsStateWithLifecycle()
    val catalogSectionsState by viewModel.catalogSectionsState.collectAsStateWithLifecycle()

    val horizontalPadding = responsivePageHorizontalPadding()

    Scaffold { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + Dimensions.PageBottomPadding
            ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SectionSpacing)
        ) {
            item(contentType = "hero") {
                when {
                    heroState.isLoading && heroState.items.isEmpty() -> {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Loading featured content...")
                            }
                        }
                    }

                    heroState.items.isEmpty() -> {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = heroState.statusMessage,
                                modifier = Modifier.padding(Dimensions.CardInternalPadding)
                            )
                        }
                    }

                    else -> {
                        HomeHeroCarousel(
                            items = heroState.items,
                            selectedId = heroState.selectedId,
                            onItemClick = onHeroClick
                        )
                    }
                }
            }

            item(contentType = "continueWatching") {
                HomeRailSection(
                    title = "Continue Watching",
                    items = continueWatchingState.items,
                    statusMessage = continueWatchingState.statusMessage,
                    actionMenuContentDescription = "Continue watching actions",
                    subtitleFor = ::continueWatchingSubtitle,
                    onItemClick = onContinueWatchingClick,
                    onHideItem = viewModel::hideContinueWatchingItem,
                    onRemoveItem = viewModel::removeContinueWatchingItem,
                    showTitleFallbackWhenNoLogo = true
                )
            }

            item(contentType = "upNext") {
                HomeRailSection(
                    title = "Up Next",
                    items = upNextState.items,
                    statusMessage = upNextState.statusMessage,
                    actionMenuContentDescription = "Up next actions",
                    subtitleFor = ::upNextSubtitle,
                    onItemClick = onContinueWatchingClick,
                    onHideItem = viewModel::hideContinueWatchingItem,
                    onRemoveItem = viewModel::removeContinueWatchingItem,
                    badgeLabel = "UP NEXT",
                    showProgressBar = true
                )
            }

            if (catalogSectionsState.sections.isNotEmpty()) {
                items(
                    items = catalogSectionsState.sections,
                    key = { it.section.key },
                    contentType = { "catalogSection" }
                ) { sectionUi ->
                    HomeCatalogSectionRow(
                        sectionUi = sectionUi,
                        onSeeAllClick = { onCatalogSeeAllClick(sectionUi.section) },
                        onItemClick = onCatalogItemClick
                    )
                }
            }
        }
    }
}

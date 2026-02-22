package com.crispy.tv.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crispy.tv.BuildConfig
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.home.HomeCatalogService
import com.crispy.tv.network.AppHttp
import com.crispy.tv.player.WatchProvider
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.components.StandardTopAppBar
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

internal data class HomeCatalogPreferenceUi(
    val key: String,
    val title: String,
    val subtitle: String
)

internal data class HomeScreenSettingsUiState(
    val preferences: HomeScreenSettingsPreferences = HomeScreenSettingsPreferences(),
    val catalogs: List<HomeCatalogPreferenceUi> = emptyList(),
    val isLoadingCatalogs: Boolean = true,
    val statusMessage: String = "Loading catalogs..."
)

internal class HomeScreenSettingsViewModel(
    private val homeCatalogService: HomeCatalogService,
    private val settingsStore: HomeScreenSettingsStore
) : ViewModel() {

    private val _uiState =
        MutableStateFlow(
            HomeScreenSettingsUiState(
                preferences = settingsStore.load()
            )
        )
    val uiState: StateFlow<HomeScreenSettingsUiState> = _uiState

    init {
        refreshCatalogs()
    }

    fun setShowRatingBadges(enabled: Boolean) {
        updatePreferences { preferences ->
            preferences.copy(showRatingBadges = enabled)
        }
    }

    fun setContinueWatchingEnabled(enabled: Boolean) {
        updatePreferences { preferences ->
            preferences.copy(continueWatchingEnabled = enabled)
        }
    }

    fun setTraktTopPicksEnabled(enabled: Boolean) {
        updatePreferences { preferences ->
            preferences.copy(traktTopPicksEnabled = enabled)
        }
    }

    fun setWatchDataSource(source: WatchProvider) {
        updatePreferences { preferences ->
            preferences.copy(watchDataSource = source)
        }
    }

    fun setCatalogEnabled(catalogKey: String, isEnabled: Boolean) {
        updatePreferences { preferences ->
            val next = preferences.disabledCatalogKeys.toMutableSet()
            if (isEnabled) {
                next.remove(catalogKey)
            } else {
                next.add(catalogKey)
            }
            preferences.copy(disabledCatalogKeys = next)
        }
    }

    fun setCatalogHero(catalogKey: String, isHero: Boolean) {
        updatePreferences { preferences ->
            val next = preferences.heroCatalogKeys.toMutableSet()
            if (isHero) {
                next.add(catalogKey)
            } else {
                next.remove(catalogKey)
            }
            preferences.copy(heroCatalogKeys = next)
        }
    }

    fun refreshCatalogs() {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    isLoadingCatalogs = true,
                    statusMessage = "Loading catalogs..."
                )
            }

            val result =
                runCatching {
                    withContext(Dispatchers.IO) {
                        homeCatalogService.listHomeCatalogSections(limit = CATALOG_LIMIT)
                    }
                }

            val loadedSections = result.getOrNull()?.first.orEmpty()
            val statusMessage =
                result.getOrNull()?.second
                    ?: (result.exceptionOrNull()?.message ?: "Unable to load catalogs right now.")

            val catalogs =
                loadedSections.map { section ->
                    HomeCatalogPreferenceUi(
                        key = catalogPreferenceKey(section),
                        title = section.title.ifBlank { section.catalogId },
                        subtitle = "${section.addonId} - ${section.mediaType.replaceFirstChar { it.uppercase() }}"
                    )
                }

            val knownKeys = catalogs.mapTo(mutableSetOf()) { item -> item.key }
            val currentPreferences = _uiState.value.preferences
            val cleanedPreferences =
                currentPreferences.copy(
                    disabledCatalogKeys =
                        currentPreferences.disabledCatalogKeys
                            .filterTo(linkedSetOf()) { key -> key in knownKeys },
                    heroCatalogKeys =
                        currentPreferences.heroCatalogKeys
                            .filterTo(linkedSetOf()) { key -> key in knownKeys }
                )
            if (cleanedPreferences != currentPreferences) {
                settingsStore.save(cleanedPreferences)
            }

            _uiState.update { state ->
                state.copy(
                    preferences = cleanedPreferences,
                    catalogs = catalogs,
                    isLoadingCatalogs = false,
                    statusMessage = statusMessage
                )
            }
        }
    }

    private fun updatePreferences(
        mutate: (HomeScreenSettingsPreferences) -> HomeScreenSettingsPreferences
    ) {
        val current = _uiState.value.preferences
        val updated = mutate(current)
        if (updated == current) {
            return
        }
        settingsStore.save(updated)
        _uiState.update { state -> state.copy(preferences = updated) }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(HomeScreenSettingsViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return HomeScreenSettingsViewModel(
                            homeCatalogService =
                             HomeCatalogService(
                                 context = appContext,
                                 addonManifestUrlsCsv = BuildConfig.METADATA_ADDON_URLS,
                                 httpClient = AppHttp.client(appContext),
                             ),
                            settingsStore = HomeScreenSettingsStore(appContext)
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }
}

@Composable
fun HomeScreenSettingsRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val viewModel: HomeScreenSettingsViewModel =
        viewModel(
            factory = remember(appContext) {
                HomeScreenSettingsViewModel.factory(appContext)
            }
        )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    HomeScreenSettingsScreen(
        uiState = uiState,
        onBack = onBack,
        onRefreshCatalogs = viewModel::refreshCatalogs,
        onShowRatingsChange = viewModel::setShowRatingBadges,
        onContinueWatchingChange = viewModel::setContinueWatchingEnabled,
        onTraktTopPicksChange = viewModel::setTraktTopPicksEnabled,
        onWatchDataSourceChange = viewModel::setWatchDataSource,
        onCatalogEnabledChange = viewModel::setCatalogEnabled,
        onCatalogHeroChange = viewModel::setCatalogHero
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreenSettingsScreen(
    uiState: HomeScreenSettingsUiState,
    onBack: () -> Unit,
    onRefreshCatalogs: () -> Unit,
    onShowRatingsChange: (Boolean) -> Unit,
    onContinueWatchingChange: (Boolean) -> Unit,
    onTraktTopPicksChange: (Boolean) -> Unit,
    onWatchDataSourceChange: (WatchProvider) -> Unit,
    onCatalogEnabledChange: (String, Boolean) -> Unit,
    onCatalogHeroChange: (String, Boolean) -> Unit
) {
    val scrollState = rememberScrollState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            StandardTopAppBar(
                title = "Home Screen",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefreshCatalogs) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Refresh catalogs"
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(scrollState)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SectionSpacing)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            SettingsSection(title = "DISPLAY") {
                ToggleSettingRow(
                    title = "Show Ratings",
                    description = "Display rating badges on media posters",
                    checked = uiState.preferences.showRatingBadges,
                    onCheckedChange = onShowRatingsChange
                )
            }

            SettingsSection(title = "PERSONALIZED CONTENT") {
                WatchDataSourceRow(
                    selectedSource = uiState.preferences.watchDataSource,
                    onSourceSelected = onWatchDataSourceChange
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = Dimensions.ListItemPadding),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                ToggleSettingRow(
                    title = "Continue Watching",
                    description = "Show your in-progress episodes and movies",
                    checked = uiState.preferences.continueWatchingEnabled,
                    onCheckedChange = onContinueWatchingChange
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = Dimensions.ListItemPadding),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                ToggleSettingRow(
                    title = "For You",
                    description = "Show personalized Trakt or Simkl recommendations on Home",
                    checked = uiState.preferences.traktTopPicksEnabled,
                    onCheckedChange = onTraktTopPicksChange
                )
            }

            SettingsSection(title = "CATALOGS") {
                when {
                    uiState.isLoadingCatalogs -> {
                        Text(
                            text = "Loading catalog sources...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Dimensions.ListItemPadding, vertical = 14.dp)
                        )
                    }

                    uiState.catalogs.isEmpty() -> {
                        Text(
                            text = "No catalogs available yet. Install an addon first.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Dimensions.ListItemPadding, vertical = 14.dp)
                        )
                    }

                    else -> {
                        uiState.catalogs.forEachIndexed { index, catalog ->
                            val isEnabled = catalog.key !in uiState.preferences.disabledCatalogKeys
                            val isHero = catalog.key in uiState.preferences.heroCatalogKeys

                            CatalogPreferenceRow(
                                catalog = catalog,
                                isEnabled = isEnabled,
                                isHero = isHero,
                                onEnabledChange = { enabled ->
                                    onCatalogEnabledChange(catalog.key, enabled)
                                },
                                onHeroChange = { hero ->
                                    onCatalogHeroChange(catalog.key, hero)
                                }
                            )

                            if (index < uiState.catalogs.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = Dimensions.ListItemPadding),
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = uiState.statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                content = content
            )
        }
    }
}

@Composable
private fun ToggleSettingRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
        },
        supportingContent = {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    )
}

@Composable
private fun WatchDataSourceRow(
    selectedSource: WatchProvider,
    onSourceSelected: (WatchProvider) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Watch Data Source",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = "Choose one source for Library, Continue Watching, and watched tags.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WatchProvider.values().forEach { source ->
                FilterChip(
                    selected = source == selectedSource,
                    onClick = { onSourceSelected(source) },
                    label = { Text(source.displayName()) }
                )
            }
        }
    }
}

@Composable
private fun CatalogPreferenceRow(
    catalog: HomeCatalogPreferenceUi,
    isEnabled: Boolean,
    isHero: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onHeroChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                text = catalog.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                text = catalog.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailingContent = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Hero",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Switch(
                        checked = isHero,
                        onCheckedChange = onHeroChange,
                        thumbContent =
                            if (isHero) {
                                {
                                    Icon(
                                        imageVector = Icons.Filled.Star,
                                        contentDescription = null
                                    )
                                }
                            } else {
                                null
                            }
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Enabled",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = onEnabledChange
                    )
                }
            }
        }
    )
}

private fun catalogPreferenceKey(section: CatalogSectionRef): String {
    return "${section.addonId.lowercase(Locale.US)}:${section.mediaType.lowercase(Locale.US)}:${section.catalogId.lowercase(Locale.US)}"
}

private fun WatchProvider.displayName(): String {
    return name.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }
}

private const val CATALOG_LIMIT = 80

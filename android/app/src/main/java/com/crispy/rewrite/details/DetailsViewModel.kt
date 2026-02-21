package com.crispy.rewrite.details

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crispy.rewrite.BuildConfig
import com.crispy.rewrite.PlaybackLabDependencies
import com.crispy.rewrite.domain.metadata.normalizeNuvioMediaId
import com.crispy.rewrite.home.HomeCatalogService
import com.crispy.rewrite.home.MediaDetails
import com.crispy.rewrite.network.AppHttp
import com.crispy.rewrite.player.MetadataLabMediaType
import com.crispy.rewrite.player.WatchHistoryLabService
import com.crispy.rewrite.player.WatchProvider
import com.crispy.rewrite.settings.HomeScreenSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class DetailsUiState(
    val itemId: String,
    val isLoading: Boolean = true,
    val details: MediaDetails? = null,
    val statusMessage: String = "",
    val isWatched: Boolean = false,
    val selectedSeason: Int? = null
) {
    val seasons: List<Int>
        get() = details?.videos?.mapNotNull { it.season }?.distinct()?.sorted() ?: emptyList()

    val selectedSeasonOrFirst: Int?
        get() = selectedSeason ?: seasons.firstOrNull()
}

class DetailsViewModel internal constructor(
    private val itemId: String,
    private val homeCatalogService: HomeCatalogService,
    private val watchHistoryService: WatchHistoryLabService,
    private val settingsStore: HomeScreenSettingsStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(DetailsUiState(itemId = itemId))
    val uiState: StateFlow<DetailsUiState> = _uiState.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Loading...") }

            val result =
                withContext(Dispatchers.IO) {
                    homeCatalogService.loadMediaDetails(rawId = itemId)
                }
            val watched =
                withContext(Dispatchers.IO) {
                    resolveWatchedState(result.details)
                }

            _uiState.update { state ->
                val details = result.details
                val firstSeason = details?.videos?.mapNotNull { it.season }?.distinct()?.minOrNull()
                state.copy(
                    isLoading = false,
                    details = details,
                    statusMessage = result.statusMessage,
                    isWatched = watched,
                    selectedSeason = state.selectedSeason ?: firstSeason
                )
            }
        }
    }

    fun onSeasonSelected(season: Int) {
        _uiState.update { it.copy(selectedSeason = season) }
    }

    private suspend fun resolveWatchedState(details: MediaDetails?): Boolean {
        val source = settingsStore.load().watchDataSource
        val normalizedTargetId = normalizeNuvioMediaId(details?.id ?: itemId).contentId.lowercase(Locale.US)
        val expectedType = details?.mediaType.toMetadataLabMediaTypeOrNull()

        return when (source) {
            WatchProvider.LOCAL -> {
                val local = watchHistoryService.listLocalHistory(limit = 250)
                local.entries.any { entry ->
                    matchesContentId(entry.contentId, normalizedTargetId) &&
                        matchesMediaType(expectedType, entry.contentType)
                }
            }

            WatchProvider.TRAKT,
            WatchProvider.SIMKL -> {
                val authState = watchHistoryService.authState()
                val connected =
                    when (source) {
                        WatchProvider.TRAKT -> authState.traktAuthenticated
                        WatchProvider.SIMKL -> authState.simklAuthenticated
                        WatchProvider.LOCAL -> false
                    }
                if (!connected) {
                    return false
                }

                val cached = watchHistoryService.getCachedProviderLibrary(limitPerFolder = 250, source = source)
                val snapshot =
                    if (cached.items.isNotEmpty() || cached.folders.isNotEmpty()) {
                        cached
                    } else {
                        watchHistoryService.listProviderLibrary(limitPerFolder = 250, source = source)
                    }
                snapshot.items.any { item ->
                    item.provider == source &&
                        source.isWatchedFolder(item.folderId) &&
                        matchesContentId(item.contentId, normalizedTargetId) &&
                        matchesMediaType(expectedType, item.contentType)
                }
            }
        }
    }

    private fun matchesMediaType(expected: MetadataLabMediaType?, actual: MetadataLabMediaType): Boolean {
        return expected == null || expected == actual
    }

    private fun matchesContentId(candidate: String, targetNormalizedId: String): Boolean {
        return normalizeNuvioMediaId(candidate).contentId.lowercase(Locale.US) == targetNormalizedId
    }

    companion object {
        fun factory(context: Context, itemId: String): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val homeCatalogService =
                        HomeCatalogService(
                            context = appContext,
                            addonManifestUrlsCsv = BuildConfig.METADATA_ADDON_URLS,
                            httpClient = AppHttp.client(appContext),
                        )
                    return DetailsViewModel(
                        itemId = itemId,
                        homeCatalogService = homeCatalogService,
                        watchHistoryService = PlaybackLabDependencies.watchHistoryServiceFactory(appContext),
                        settingsStore = HomeScreenSettingsStore(appContext)
                    ) as T
                }
            }
        }
    }
}

private fun String?.toMetadataLabMediaTypeOrNull(): MetadataLabMediaType? {
    return when (this?.lowercase(Locale.US)) {
        "movie" -> MetadataLabMediaType.MOVIE
        "series", "show", "tv" -> MetadataLabMediaType.SERIES
        else -> null
    }
}

private fun WatchProvider.isWatchedFolder(folderId: String): Boolean {
    val normalized = folderId.trim().lowercase(Locale.US)
    return when (this) {
        WatchProvider.LOCAL -> false
        WatchProvider.TRAKT -> normalized == "watched"
        WatchProvider.SIMKL -> normalized.startsWith("completed")
    }
}

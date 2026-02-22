package com.crispy.rewrite.details

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crispy.rewrite.BuildConfig
import com.crispy.rewrite.PlaybackDependencies
import com.crispy.rewrite.domain.metadata.normalizeNuvioMediaId
import com.crispy.rewrite.home.HomeCatalogService
import com.crispy.rewrite.home.MediaDetails
import com.crispy.rewrite.metadata.TmdbImdbIdResolver
import com.crispy.rewrite.network.AppHttp
import com.crispy.rewrite.player.MetadataLabMediaType
import com.crispy.rewrite.player.WatchHistoryService
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
    val isInWatchlist: Boolean = false,
    val isRated: Boolean = false,
    val userRating: Int? = null,
    val isMutating: Boolean = false,
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
    private val watchHistoryService: WatchHistoryService,
    private val settingsStore: HomeScreenSettingsStore,
    private val tmdbImdbIdResolver: TmdbImdbIdResolver
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

            val enrichedDetails =
                withContext(Dispatchers.IO) {
                    result.details?.let { ensureImdbId(it) }
                }
            val providerState =
                withContext(Dispatchers.IO) {
                    resolveProviderState(enrichedDetails)
                }

            _uiState.update { state ->
                val details = enrichedDetails
                val firstSeason = details?.videos?.mapNotNull { it.season }?.distinct()?.minOrNull()
                state.copy(
                    isLoading = false,
                    details = details,
                    statusMessage = result.statusMessage,
                    isWatched = providerState.isWatched,
                    isInWatchlist = providerState.isInWatchlist,
                    isRated = providerState.isRated,
                    userRating = providerState.userRating,
                    selectedSeason = state.selectedSeason ?: firstSeason
                )
            }
        }
    }

    fun onSeasonSelected(season: Int) {
        _uiState.update { it.copy(selectedSeason = season) }
    }

    fun toggleWatchlist() {
        val details = uiState.value.details ?: return
        val mediaType = details.mediaType.toMetadataLabMediaTypeOrNull() ?: MetadataLabMediaType.MOVIE
        val source = settingsStore.load().watchDataSource
        if (source == WatchProvider.LOCAL) {
            _uiState.update { it.copy(statusMessage = "Connect Trakt or Simkl to use watchlist.") }
            return
        }

        viewModelScope.launch {
            val desired = !_uiState.value.isInWatchlist
            _uiState.update { it.copy(isMutating = true) }

            val enriched =
                withContext(Dispatchers.IO) {
                    ensureImdbId(details)
                }
            if (enriched.imdbId != details.imdbId) {
                _uiState.update { it.copy(details = enriched) }
            }
            if (source == WatchProvider.SIMKL && enriched.imdbId == null) {
                _uiState.update {
                    it.copy(
                        isMutating = false,
                        statusMessage = "Couldn't resolve an IMDb id for this title (required for Simkl)."
                    )
                }
                return@launch
            }

            val request =
                com.crispy.rewrite.player.WatchHistoryRequest(
                    contentId = enriched.id,
                    contentType = mediaType,
                    title = enriched.title,
                    remoteImdbId = enriched.imdbId
                )
            val result =
                withContext(Dispatchers.IO) {
                    watchHistoryService.setInWatchlist(request, desired, source)
                }
            val success =
                when (source) {
                    WatchProvider.TRAKT -> result.syncedToTrakt
                    WatchProvider.SIMKL -> result.syncedToSimkl
                    WatchProvider.LOCAL -> false
                }
            _uiState.update {
                it.copy(
                    isMutating = false,
                    statusMessage = result.statusMessage,
                    isInWatchlist = if (success) desired else it.isInWatchlist
                )
            }
        }
    }

    fun toggleWatched() {
        val details = uiState.value.details ?: return
        val mediaType = details.mediaType.toMetadataLabMediaTypeOrNull() ?: MetadataLabMediaType.MOVIE
        if (mediaType == MetadataLabMediaType.SERIES) {
            _uiState.update { it.copy(statusMessage = "Marking an entire series as watched isn't supported yet. Mark episodes from the episode list.") }
            return
        }
        val source = settingsStore.load().watchDataSource

        viewModelScope.launch {
            val desired = !_uiState.value.isWatched
            _uiState.update { it.copy(isMutating = true) }

            val enriched =
                withContext(Dispatchers.IO) {
                    ensureImdbId(details)
                }
            if (enriched.imdbId != details.imdbId) {
                _uiState.update { it.copy(details = enriched) }
            }
            if (source == WatchProvider.SIMKL && enriched.imdbId == null) {
                _uiState.update {
                    it.copy(
                        isMutating = false,
                        statusMessage = "Couldn't resolve an IMDb id for this title (required for Simkl)."
                    )
                }
                return@launch
            }

            val request =
                com.crispy.rewrite.player.WatchHistoryRequest(
                    contentId = enriched.id,
                    contentType = mediaType,
                    title = enriched.title,
                    remoteImdbId = enriched.imdbId
                )
            val result =
                withContext(Dispatchers.IO) {
                    if (desired) {
                        watchHistoryService.markWatched(request, source)
                    } else {
                        watchHistoryService.unmarkWatched(request, source)
                    }
                }
            val success =
                when (source) {
                    WatchProvider.TRAKT -> result.syncedToTrakt
                    WatchProvider.SIMKL -> result.syncedToSimkl
                    WatchProvider.LOCAL -> true
                }
            _uiState.update {
                it.copy(
                    isMutating = false,
                    statusMessage = result.statusMessage,
                    isWatched = if (success) desired else it.isWatched
                )
            }
        }
    }

    fun setRating(rating: Int?) {
        val details = uiState.value.details ?: return
        val mediaType = details.mediaType.toMetadataLabMediaTypeOrNull() ?: MetadataLabMediaType.MOVIE
        val source = settingsStore.load().watchDataSource
        if (source == WatchProvider.LOCAL) {
            _uiState.update { it.copy(statusMessage = "Connect Trakt or Simkl to rate.") }
            return
        }
        if (source == WatchProvider.SIMKL && rating == null) {
            _uiState.update { it.copy(statusMessage = "Removing ratings is not supported for Simkl yet.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isMutating = true) }

            val enriched =
                withContext(Dispatchers.IO) {
                    ensureImdbId(details)
                }
            if (enriched.imdbId != details.imdbId) {
                _uiState.update { it.copy(details = enriched) }
            }
            if (source == WatchProvider.SIMKL && enriched.imdbId == null) {
                _uiState.update {
                    it.copy(
                        isMutating = false,
                        statusMessage = "Couldn't resolve an IMDb id for this title (required for Simkl)."
                    )
                }
                return@launch
            }

            val request =
                com.crispy.rewrite.player.WatchHistoryRequest(
                    contentId = enriched.id,
                    contentType = mediaType,
                    title = enriched.title,
                    remoteImdbId = enriched.imdbId
                )
            val result =
                withContext(Dispatchers.IO) {
                    watchHistoryService.setRating(request, rating, source)
                }
            val success =
                when (source) {
                    WatchProvider.TRAKT -> result.syncedToTrakt
                    WatchProvider.SIMKL -> result.syncedToSimkl
                    WatchProvider.LOCAL -> false
                }
            _uiState.update {
                it.copy(
                    isMutating = false,
                    statusMessage = result.statusMessage,
                    isRated = if (success) rating != null else it.isRated,
                    userRating = if (success) rating else it.userRating
                )
            }
        }
    }

    private suspend fun ensureImdbId(details: MediaDetails): MediaDetails {
        val fromId = details.id.trim().takeIf { it.startsWith("tt", ignoreCase = true) }?.lowercase(Locale.US)
        val fromField = details.imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) }?.lowercase(Locale.US)
        val existing = fromId ?: fromField
        if (existing != null) {
            return if (fromField == existing) details else details.copy(imdbId = existing)
        }

        val mediaType = details.mediaType.toMetadataLabMediaTypeOrNull() ?: return details
        val resolved = tmdbImdbIdResolver.resolveImdbId(details.id, mediaType) ?: return details
        return details.copy(imdbId = resolved)
    }

    private data class ProviderState(
        val isWatched: Boolean,
        val isInWatchlist: Boolean,
        val isRated: Boolean,
        val userRating: Int?
    )

    private suspend fun resolveProviderState(details: MediaDetails?): ProviderState {
        val source = settingsStore.load().watchDataSource
        val normalizedTargetId = normalizeNuvioMediaId(details?.id ?: itemId).contentId.lowercase(Locale.US)
        val normalizedImdbId =
            details?.imdbId?.let { imdb ->
                normalizeNuvioMediaId(imdb).contentId.lowercase(Locale.US)
            }
        val expectedType = details?.mediaType.toMetadataLabMediaTypeOrNull()

        fun matchesItemContent(itemContentId: String): Boolean {
            return matchesContentId(itemContentId, normalizedTargetId) ||
                (normalizedImdbId != null && matchesContentId(itemContentId, normalizedImdbId))
        }

        return when (source) {
            WatchProvider.LOCAL -> {
                val local = watchHistoryService.listLocalHistory(limit = 250)
                val watched =
                    local.entries.any { entry ->
                        matchesItemContent(entry.contentId) && matchesMediaType(expectedType, entry.contentType)
                    }
                ProviderState(
                    isWatched = watched,
                    isInWatchlist = false,
                    isRated = false,
                    userRating = null
                )
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
                    return ProviderState(false, false, false, null)
                }

                val cached = watchHistoryService.getCachedProviderLibrary(limitPerFolder = 250, source = source)
                val snapshot =
                    if (cached.items.isNotEmpty() || cached.folders.isNotEmpty()) {
                        cached
                    } else {
                        watchHistoryService.listProviderLibrary(limitPerFolder = 250, source = source)
                    }

                val watched =
                    snapshot.items.any { item ->
                        item.provider == source &&
                            source.isWatchedFolder(item.folderId) &&
                            matchesItemContent(item.contentId) &&
                            matchesMediaType(expectedType, item.contentType)
                    }

                val watchlistFolderId =
                    when (source) {
                        WatchProvider.TRAKT -> "watchlist"
                        WatchProvider.SIMKL -> "plantowatch"
                        WatchProvider.LOCAL -> ""
                    }
                val inWatchlist =
                    snapshot.items.any { item ->
                        item.provider == source &&
                            item.folderId == watchlistFolderId &&
                            matchesItemContent(item.contentId) &&
                            matchesMediaType(expectedType, item.contentType)
                    }

                val isRated =
                    snapshot.items.any { item ->
                        item.provider == source &&
                            item.folderId == "ratings" &&
                            matchesItemContent(item.contentId) &&
                            matchesMediaType(expectedType, item.contentType)
                    }

                ProviderState(
                    isWatched = watched,
                    isInWatchlist = inWatchlist,
                    isRated = isRated,
                    userRating = null
                )
            }
        }
    }

    // resolveWatchedState removed in favor of resolveProviderState.

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
                    val httpClient = AppHttp.client(appContext)
                    val homeCatalogService =
                        HomeCatalogService(
                            context = appContext,
                            addonManifestUrlsCsv = BuildConfig.METADATA_ADDON_URLS,
                            httpClient = httpClient,
                        )
                    val tmdbImdbIdResolver =
                        TmdbImdbIdResolver(
                            apiKey = BuildConfig.TMDB_API_KEY,
                            httpClient = httpClient
                        )
                    return DetailsViewModel(
                        itemId = itemId,
                        homeCatalogService = homeCatalogService,
                        watchHistoryService = PlaybackDependencies.watchHistoryServiceFactory(appContext),
                        settingsStore = HomeScreenSettingsStore(appContext),
                        tmdbImdbIdResolver = tmdbImdbIdResolver
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

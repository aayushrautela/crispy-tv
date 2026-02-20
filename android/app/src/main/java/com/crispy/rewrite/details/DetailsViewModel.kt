package com.crispy.rewrite.details

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crispy.rewrite.BuildConfig
import com.crispy.rewrite.home.HomeCatalogService
import com.crispy.rewrite.home.MediaDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DetailsUiState(
    val itemId: String,
    val isLoading: Boolean = true,
    val details: MediaDetails? = null,
    val statusMessage: String = "",
    val selectedSeason: Int? = null
) {
    val seasons: List<Int>
        get() = details?.videos?.mapNotNull { it.season }?.distinct()?.sorted() ?: emptyList()

    val selectedSeasonOrFirst: Int?
        get() = selectedSeason ?: seasons.firstOrNull()
}

class DetailsViewModel(
    private val itemId: String,
    private val homeCatalogService: HomeCatalogService
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

            _uiState.update { state ->
                val details = result.details
                val firstSeason = details?.videos?.mapNotNull { it.season }?.distinct()?.minOrNull()
                state.copy(
                    isLoading = false,
                    details = details,
                    statusMessage = result.statusMessage,
                    selectedSeason = state.selectedSeason ?: firstSeason
                )
            }
        }
    }

    fun onSeasonSelected(season: Int) {
        _uiState.update { it.copy(selectedSeason = season) }
    }

    companion object {
        fun factory(context: Context, itemId: String): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val homeCatalogService = HomeCatalogService(appContext, BuildConfig.METADATA_ADDON_URLS)
                    return DetailsViewModel(itemId = itemId, homeCatalogService = homeCatalogService) as T
                }
            }
        }
    }
}

package com.crispy.rewrite.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crispy.rewrite.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeUiState(
    val heroItems: List<HomeHeroItem> = emptyList(),
    val selectedHeroId: String? = null,
    val isLoading: Boolean = true,
    val statusMessage: String = "Loading featured content..."
) {
    val selectedHero: HomeHeroItem?
        get() = heroItems.firstOrNull { it.id == selectedHeroId } ?: heroItems.firstOrNull()
}

class HomeViewModel(
    private val homeCatalogService: HomeCatalogService
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    isLoading = true,
                    statusMessage = "Loading featured content..."
                )
            }

            val result = withContext(Dispatchers.IO) {
                homeCatalogService.loadHeroItems(limit = 10)
            }
            _uiState.update { current ->
                val selectedId =
                    current.selectedHeroId?.takeIf { id -> result.items.any { it.id == id } }
                        ?: result.items.firstOrNull()?.id
                current.copy(
                    heroItems = result.items,
                    selectedHeroId = selectedId,
                    isLoading = false,
                    statusMessage = result.statusMessage
                )
            }
        }
    }

    fun onHeroSelected(heroId: String) {
        _uiState.update { current ->
            if (current.heroItems.none { it.id == heroId }) {
                current
            } else {
                current.copy(selectedHeroId = heroId)
            }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return HomeViewModel(
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

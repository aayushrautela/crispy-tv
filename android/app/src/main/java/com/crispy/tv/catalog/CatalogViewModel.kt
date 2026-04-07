package com.crispy.tv.catalog

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.home.RecommendationCatalogService
import kotlinx.coroutines.flow.Flow

class CatalogViewModel(
    recommendationCatalogService: RecommendationCatalogService,
    section: CatalogSectionRef
) : ViewModel() {

    val items: Flow<PagingData<CatalogItem>> =
        Pager(
            config = PagingConfig(
                pageSize = 30,
                initialLoadSize = 30,
                prefetchDistance = 10,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                CatalogPagingSource(recommendationCatalogService, section)
            }
        ).flow.cachedIn(viewModelScope)

    companion object {
        fun factory(context: Context, section: CatalogSectionRef): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(CatalogViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return CatalogViewModel(
                            recommendationCatalogService = SupabaseServicesProvider.recommendationCatalogService(appContext),
                            section = section
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }
}

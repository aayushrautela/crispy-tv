package com.crispy.tv.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.crispy.tv.home.HomeCatalogService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow

/**
 * The paging view model for one catalog section.
 *
 * **`ioDispatcher` is required and has no default**, and that is the whole point
 * of it. `Dispatchers.IO` is **public on the JVM and internal on Kotlin/Native**,
 * so the class used to name it and that alone pinned it to `androidMain`;
 * a default would have made this file look fixed on every machine that is not a
 * Mac, and the first symptom would be a red `apple.yml` run rather than a red
 * desktop build. `Dispatchers.Default` would be worse still: it compiles
 * everywhere and silently puts a blocking add-on fetch on a CPU-sized pool.
 *
 * This is the `CatalogPagingSource` shape, and that file's KDoc already recorded
 * the reasoning for its own identical slot -- so the port needed no new
 * abstraction, only the same one applied one level up.
 */
class CatalogViewModel(
    homeCatalogService: HomeCatalogService,
    section: CatalogSectionRef,
    ioDispatcher: CoroutineDispatcher,
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
                CatalogPagingSource(homeCatalogService, section, ioDispatcher)
            }
        ).flow.cachedIn(viewModelScope)
}
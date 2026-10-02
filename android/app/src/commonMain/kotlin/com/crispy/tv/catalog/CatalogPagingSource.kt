package com.crispy.tv.catalog

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.crispy.tv.home.HomeCatalogService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * [ioDispatcher] is required and has no default, and that is the whole point of it.
 *
 * `Dispatchers.IO` is **public on the JVM and internal on Kotlin/Native**, so it
 * compiles in `commonMain` on desktop and cannot be accessed at all on
 * `iosArm64`. **A default would have made this file look fixed on every machine
 * that is not a Mac** -- and the first symptom would be three red `apple.yml`
 * runs, not a red desktop build. `Dispatchers.Default` would be worse still: it
 * compiles everywhere and silently puts blocking I/O on a CPU-sized pool.
 *
 * **This is the `DetailsMetadataLoader` shape, and it was already recorded.**
 * The same reasoning is why `LibraryRoute.kt`'s `clock` slot has no default.
 */
class CatalogPagingSource(
    private val homeCatalogService: HomeCatalogService,
    private val section: CatalogSectionRef,
    private val ioDispatcher: CoroutineDispatcher,
) : PagingSource<Int, CatalogItem>() {

    /**
     * Keys already handed to the grid, for the reason
     * `CatalogItem.lazyKey` records: an add-on's pages overlap, and a repeated
     * key in a lazy grid is a crash rather than a duplicate card.
     */
    private val acceptedKeys = mutableSetOf<String>()

    override fun getRefreshKey(state: PagingState<Int, CatalogItem>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey?.plus(1) ?: anchorPage?.nextKey?.minus(1)
        }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, CatalogItem> {
        val page = params.key ?: 1
        val pageSize = params.loadSize.coerceAtLeast(1)

        return runCatching {
            val result = withContext(ioDispatcher) {
                homeCatalogService.fetchCatalogPage(
                    section = section,
                    page = page,
                    pageSize = pageSize
                )
            }
            // Measured on the page the add-on returned, not on what survives the
            // filter: a full page of already-seen titles still means there is a
            // page 3, and reading the filtered size here would end the list one
            // screen early.
            val nextKey = if (result.items.size < pageSize) null else page + 1
            LoadResult.Page(
                data = acceptedKeys.acceptFirstCatalogItems(result.items),
                prevKey = if (page == 1) null else page - 1,
                nextKey = nextKey
            )
        }.getOrElse { error ->
            LoadResult.Error(error)
        }
    }
}

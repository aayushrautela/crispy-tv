package com.crispy.tv.discover

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.catalog.acceptFirstCatalogItems
import kotlinx.coroutines.CancellationException

/**
 * Pages of discover results, and **the source of a production crash**: the grid
 * that renders these items keys them by [CatalogItem.lazyKey], and a title the
 * backend returned on two pages of the same pager — which it does whenever the
 * server re-sorts `popularity` between one request and the next — was appended
 * twice, so Compose threw `Key "show:…" was already used` while flinging.
 *
 * So the page is filtered rather than the key patched. [acceptedKeys] is per
 * instance, which is per generation: `Pager` builds a new source after every
 * invalidation, so a refresh is empty without anything having to clear it.
 */
class BrowsePagingSource(
    private val repository: BackendBrowseRepository,
    private val type: String,
    private val genre: String?,
    private val sort: String,
) : PagingSource<Int, CatalogItem>() {

    private val acceptedKeys = mutableSetOf<String>()

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, CatalogItem> {
        val nextPage = params.key ?: 0
        return try {
            val payload =
                repository.browsePage(
                    type = type,
                    genre = genre,
                    sort = sort,
                    page = nextPage,
                )
            val prevKey = if (nextPage > 0) nextPage - 1 else null
            val nextKey = if (payload.hasMore) nextPage + 1 else null
            LoadResult.Page(
                data = acceptedKeys.acceptFirstCatalogItems(payload.items),
                prevKey = prevKey,
                nextKey = nextKey,
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            LoadResult.Error(error)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, CatalogItem>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            state.closestPageToPosition(anchorPosition)?.prevKey?.plus(1)
                ?: state.closestPageToPosition(anchorPosition)?.nextKey?.minus(1)
        }
    }
}
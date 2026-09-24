package com.crispy.tv.discover

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.crispy.tv.catalog.CatalogItem
import kotlinx.coroutines.CancellationException

class BrowsePagingSource(
    private val repository: BackendBrowseRepository,
    private val type: String,
    private val genre: String?,
    private val sort: String,
) : PagingSource<Int, CatalogItem>() {

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
                data = payload.items,
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
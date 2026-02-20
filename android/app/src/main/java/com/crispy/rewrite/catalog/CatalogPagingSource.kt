package com.crispy.rewrite.catalog

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.crispy.rewrite.home.HomeCatalogService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CatalogPagingSource(
    private val homeCatalogService: HomeCatalogService,
    private val section: CatalogSectionRef
) : PagingSource<Int, CatalogItem>() {

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
            val result = withContext(Dispatchers.IO) {
                homeCatalogService.fetchCatalogPage(
                    section = section,
                    page = page,
                    pageSize = pageSize
                )
            }
            val items = result.items
            val nextKey = if (items.size < pageSize) null else page + 1
            LoadResult.Page(
                data = items,
                prevKey = if (page == 1) null else page - 1,
                nextKey = nextKey
            )
        }.getOrElse { error ->
            LoadResult.Error(error)
        }
    }
}

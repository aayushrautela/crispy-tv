package com.crispy.tv.catalog

import androidx.paging.PagingSource
import com.crispy.tv.domain.home.HomeCatalogPresentation
import com.crispy.tv.domain.home.HomeCatalogSource
import com.crispy.tv.domain.home.HomeRandomCandidate
import com.crispy.tv.home.HomeCatalogService
import com.crispy.tv.home.HomePrimaryFeedLoadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The same defect as `BrowsePagingSourceTest`, in the other paging grid: an
 * add-on's pages overlap, and `CatalogScreen` keys its cards by
 * [CatalogItem.lazyKey], so a repeated key is
 * `IllegalArgumentException: Key "…" was already used` rather than a second card.
 *
 * The case worth having here is the second one. `CatalogPagingSource` decides
 * whether there is a page 3 by comparing the **add-on's** page size against
 * `loadSize`, so a page made entirely of already-seen titles must still leave
 * `nextKey` set — reading the filtered size instead ends the list one screen
 * early, and no case about duplicates would have caught it.
 */
class CatalogPagingSourceTest {

    private val service = FakeHomeCatalogService()

    @Test
    fun `a title the add-on sends on two pages is handed to the grid once`() = runTest {
        service.answerPage(catalogItem("a"), catalogItem("b"))
        service.answerPage(catalogItem("b"), catalogItem("c"))
        val subject = source()

        val first = resultOf(subject.load(refresh(loadSize = 20)))
        val second = resultOf(subject.load(append(key = 2, loadSize = 20)))

        assertEquals(listOf("a", "b"), first.data.map { it.itemId })
        assertEquals(listOf("c"), second.data.map { it.itemId })
        val keys = (first.data + second.data).map { it.lazyKey() }
        assertEquals("the grid key is what the crash was about", keys.size, keys.toSet().size)
    }

    @Test
    fun `a page of already-seen titles still offers the next page`() = runTest {
        service.answerPage(catalogItem("a"), catalogItem("b"))
        service.answerPage(catalogItem("a"), catalogItem("b"))
        val subject = source()

        resultOf(subject.load(refresh(loadSize = 2)))
        val second = resultOf(subject.load(append(key = 2, loadSize = 2)))

        assertEquals(emptyList<CatalogItem>(), second.data)
        assertEquals("a full page means there is a page 3", 3, second.nextKey)
    }

    @Test
    fun `a short page ends the list even when every item was new`() = runTest {
        service.answerPage(catalogItem("a"), catalogItem("b"), catalogItem("c"))

        val page = resultOf(source().load(refresh(loadSize = 10)))

        assertEquals(listOf("a", "b", "c"), page.data.map { it.itemId })
        assertEquals(null, page.nextKey)
    }

    @Test
    fun `a new source remembers nothing`() = runTest {
        service.answerPage(catalogItem("a"), catalogItem("b"))
        service.answerPage(catalogItem("a"), catalogItem("b"))
        resultOf(source().load(refresh()))

        val fresh = resultOf(source().load(refresh()))

        assertEquals(listOf("a", "b"), fresh.data.map { it.itemId })
    }

    @Test
    fun `a failure is a page error rather than a thrown exception`() = runTest {
        service.failWith(IllegalStateException("addon is down"))

        val result = source().load(refresh())

        assertTrue("expected LoadResult.Error, got $result", result is PagingSource.LoadResult.Error)
    }

    // --- double ---------------------------------------------------------------

    /** Answers [fetchCatalogPage] from a queue, one page per call, in order. */
    private class FakeHomeCatalogService : HomeCatalogService {
        private val pages = mutableListOf<List<CatalogItem>>()
        private var calls = 0
        private var failure: Throwable? = null

        fun answerPage(vararg items: CatalogItem) = apply { pages += items.toList() }

        fun failWith(error: Throwable) = apply { failure = error }

        override suspend fun fetchCatalogPage(
            section: CatalogSectionRef,
            page: Int,
            pageSize: Int,
        ): CatalogPageResult {
            failure?.let { throw it }
            val items = pages.getOrNull(calls).orEmpty()
            calls++
            return CatalogPageResult(items = items)
        }

        override suspend fun loadPrimaryHomeFeed(sectionLimit: Int): HomePrimaryFeedLoadResult =
            HomePrimaryFeedLoadResult()

        override suspend fun loadCachedPrimaryHomeFeed(sectionLimit: Int): HomePrimaryFeedLoadResult? = null

        override suspend fun cachedHomeExpiresAtMs(): Long? = null

        override suspend fun loadRandomCandidates(): List<HomeRandomCandidate> = emptyList()
    }

    // --- fixtures -------------------------------------------------------------

    private fun source() = CatalogPagingSource(
        homeCatalogService = service,
        section = SECTION,
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun refresh(key: Int? = null, loadSize: Int = 20): PagingSource.LoadParams<Int> =
        PagingSource.LoadParams.Refresh(key = key, loadSize = loadSize, placeholdersEnabled = false)

    private fun append(key: Int, loadSize: Int = 20): PagingSource.LoadParams<Int> =
        PagingSource.LoadParams.Append(key = key, loadSize = loadSize, placeholdersEnabled = false)

    private fun resultOf(
        result: PagingSource.LoadResult<Int, CatalogItem>,
    ): PagingSource.LoadResult.Page<Int, CatalogItem> {
        assertTrue("expected a page but got $result", result is PagingSource.LoadResult.Page)
        return result as PagingSource.LoadResult.Page
    }

    private fun catalogItem(id: String) = CatalogItem(
        id = id,
        itemId = id,
        title = "Title $id",
        artworkUrl = "https://img.test/$id.jpg",
        addonId = "addon-1",
        type = "movie",
    )

    private companion object {
        val SECTION = CatalogSectionRef(
            catalogId = "trending",
            source = HomeCatalogSource.PUBLIC,
            presentation = HomeCatalogPresentation.RAIL,
        )
    }
}
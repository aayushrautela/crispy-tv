package com.crispy.tv.library

import androidx.paging.PagingSource
import com.crispy.tv.accounts.FixedBackendContextResolver
import com.crispy.tv.accounts.RecordingBackendApi
import com.crispy.tv.backend.BackendContext
import com.crispy.tv.backend.ClientMediaCardQueryResult
import com.crispy.tv.catalog.CatalogItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the three decisions `load` makes and the two caches it consults.
 *
 * The class became constructible in a test only after both of its dependencies
 * became ports, which is the point of the `LibraryDiskCache` split: before it,
 * the paging source was unreachable because its cache took a `Context`.
 */
class LibraryPagingSourceTest {
    private val context = BackendContext(accessToken = "token-1", profileId = "profile-1")

    private fun source(
        backend: RecordingBackendApi,
        resolver: FixedBackendContextResolver,
        cache: FakeLibraryDiskCache,
        sectionId: String = "history",
        appliedGenerationMs: Long? = null,
    ) = LibraryPagingSource(
        backend = backend,
        backendContextResolver = resolver,
        sectionId = sectionId,
        libraryCache = cache,
        appliedGenerationMsProvider = { appliedGenerationMs },
    )

    private fun refresh(key: String?, loadSize: Int = 20): PagingSource.LoadParams<String> =
        PagingSource.LoadParams.Refresh(
            key = key,
            loadSize = loadSize,
            placeholdersEnabled = false,
        )

    private fun resultOf(result: PagingSource.LoadResult<String, CatalogItem>): PagingSource.LoadResult.Page<String, CatalogItem> {
        assertTrue("expected a page but got $result", result is PagingSource.LoadResult.Page)
        return result as PagingSource.LoadResult.Page
    }

    @Test
    fun `the first page is served from the cache without asking the backend`() = runTest {
        val backend = RecordingBackendApi()
        val cache = FakeLibraryDiskCache(cachedPage())
        val subject = source(backend, FixedBackendContextResolver(context), cache)

        val page = resultOf(subject.load(refresh(key = null)))

        assertEquals(listOf("cached-1"), page.data.map { it.id })
        assertEquals(listOf("profile-1"), cache.readCalls.map { it.profileId })
        assertEquals("the cache answered, so the network was never reached", 0, totalNetworkCalls(backend))
        assertEquals("a cache hit must not be written back", 0, cache.writeCalls.size)
    }

    @Test
    fun `a later page skips the cache in both directions`() = runTest {
        val backend = RecordingBackendApi()
        backend.answerSections(history = listOf(emptyResult()))
        val cache = FakeLibraryDiskCache(cachedPage())
        val subject = source(backend, FixedBackendContextResolver(context), cache)

        val page = resultOf(subject.load(refresh(key = "cursor-1")))

        assertTrue(page.data.isEmpty())
        assertEquals("only the first page is cached", 0, cache.readCalls.size)
        assertEquals("only the first page is cached", 0, cache.writeCalls.size)
        assertEquals(1, totalNetworkCalls(backend))
    }

    @Test
    fun `a first page with no cache hit reaches the network and writes the cache`() = runTest {
        val backend = RecordingBackendApi()
        backend.answerSections(history = listOf(emptyResult()))
        val cache = FakeLibraryDiskCache(page = null)
        val subject = source(backend, FixedBackendContextResolver(context), cache, appliedGenerationMs = 555L)

        subject.load(refresh(key = null))

        assertEquals(1, cache.readCalls.size)
        assertEquals(1, cache.writeCalls.size)
        assertEquals("the provider supplies the generation stamped on the cache entry", 555L, cache.writeCalls.single().appliedGenerationMs)
    }

    @Test
    fun `no resolved profile is an error naming the action`() = runTest {
        val backend = RecordingBackendApi()
        val resolver = FixedBackendContextResolver(null)
        val subject = source(backend, resolver, FakeLibraryDiskCache())

        val result = subject.load(refresh(key = null))

        assertTrue("expected an error but got $result", result is PagingSource.LoadResult.Error)
        val message = (result as PagingSource.LoadResult.Error).throwable.message
        assertEquals("Sign in and select a profile to load your library.", message)
        assertEquals("the cache is keyed by profile, so it is not consulted", 0, FakeLibraryDiskCache().readCalls.size)
    }

    @Test
    fun `an unconfigured backend never asks for a profile`() = runTest {
        val backend = RecordingBackendApi().apply { configured = false }
        val resolver = FixedBackendContextResolver(context)
        val subject = source(backend, resolver, FakeLibraryDiskCache())

        val result = subject.load(refresh(key = null))

        assertTrue("expected an error but got $result", result is PagingSource.LoadResult.Error)
        assertEquals("an unconfigured client cannot resolve a profile", 0, resolver.resolveCalls)
    }

    @Test
    fun `getRefreshKey is always null so a refresh restarts from the first page`() {
        val subject = source(
            RecordingBackendApi(),
            FixedBackendContextResolver(context),
            FakeLibraryDiskCache(),
        )

        assertNull(subject.getRefreshKey(androidx.paging.PagingState(emptyList(), null, androidx.paging.PagingConfig(20), 0)))
    }

    @Test
    fun `a blank cursor is not a next key even when there is more`() = runTest {
        val backend = RecordingBackendApi()
        backend.answerSections(history = listOf(emptyResult()))
        val cache = FakeLibraryDiskCache(
            LibraryCachedPage(items = emptyList(), nextCursor = "  ", hasMore = true, appliedGenerationMs = null),
        )
        val subject = source(backend, FixedBackendContextResolver(context), cache)

        val page = resultOf(subject.load(refresh(key = null)))

        assertNull("a blank cursor would loop the pager forever", page.nextKey)
    }

    /** A backend answer with no cards; these cases assert the plumbing, not the mapping. */
    @Test
    fun `a network first page with more results exposes the cursor as the next key`() = runTest {
        val backend = RecordingBackendApi()
        backend.answerSections(history = listOf(resultWithCursor("cursor-1", hasMore = true)))
        val subject = source(backend, FixedBackendContextResolver(context), FakeLibraryDiskCache(page = null))

        val page = resultOf(subject.load(refresh(key = null)))

        assertEquals("cursor-1", page.nextKey)
    }

    @Test
    fun `a network first page reporting a cursor but no more results has no next key`() = runTest {
        val backend = RecordingBackendApi()
        backend.answerSections(history = listOf(resultWithCursor("cursor-1", hasMore = false)))
        val subject = source(backend, FixedBackendContextResolver(context), FakeLibraryDiskCache(page = null))

        val page = resultOf(subject.load(refresh(key = null)))

        assertNull("a cursor with nothing after it would loop the pager", page.nextKey)
    }

    @Test
    fun `a blank cursor from the network is not a next key even when there is more`() = runTest {
        val backend = RecordingBackendApi()
        backend.answerSections(history = listOf(resultWithCursor("  ", hasMore = true)))
        val subject = source(backend, FixedBackendContextResolver(context), FakeLibraryDiskCache(page = null))

        val page = resultOf(subject.load(refresh(key = null)))

        assertNull("a blank cursor would loop the pager forever", page.nextKey)
    }

    /** The cached branch has its own copy of the same rule, so the pair is pinned too. */
    @Test
    fun `a cached page with more results exposes the cursor as the next key`() = runTest {
        val cache = FakeLibraryDiskCache(
            LibraryCachedPage(items = emptyList(), nextCursor = "cached-1", hasMore = true, appliedGenerationMs = null),
        )
        val subject = source(RecordingBackendApi(), FixedBackendContextResolver(context), cache)

        val page = resultOf(subject.load(refresh(key = null)))

        assertEquals("cached-1", page.nextKey)
    }

    private fun resultWithCursor(nextCursor: String?, hasMore: Boolean) =
        ClientMediaCardQueryResult(
            items = emptyList(),
            startIndex = 0,
            totalRecordCount = 0,
            nextCursor = nextCursor,
            hasMore = hasMore,
        )

    private fun emptyResult() =
        ClientMediaCardQueryResult(
            items = emptyList(),
            startIndex = 0,
            totalRecordCount = 0,
            nextCursor = null,
            hasMore = false,
        )

    private fun catalogItem(id: String) = CatalogItem(
        id = id,
        itemId = id,
        title = "Title $id",
        artworkUrl = null,
        addonId = "addon-1",
        type = "movie",
    )

    private fun cachedPage() =
        LibraryCachedPage(
            items = listOf(catalogItem("cached-1")),
            nextCursor = null,
            hasMore = false,
            appliedGenerationMs = null,
        )

    private fun totalNetworkCalls(backend: RecordingBackendApi) =
        backend.listWatchHistoryCalls.size + backend.listWatchlistCalls.size + backend.listRatingsCalls.size
}

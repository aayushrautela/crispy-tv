package com.crispy.tv.discover

import androidx.paging.PagingSource
import com.crispy.tv.accounts.RecordingBackendApi
import com.crispy.tv.accounts.Session
import com.crispy.tv.backend.BrowseTitlesResponse
import com.crispy.tv.backend.ClientImages
import com.crispy.tv.backend.ClientMediaCard
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.catalog.lazyKey
import com.crispy.tv.images.ResponsiveImageSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production crash, in the terms it arrived in.
 *
 * A `LazyVerticalGrid` on Discover keys its cards by [CatalogItem.lazyKey], and a
 * title the server returned on two pages of one pager — which it does whenever
 * `popularity` is re-evaluated between one request and the next — was appended
 * twice, so Compose threw
 * `IllegalArgumentException: Key "show:2863a087c6634b668d337e5ac4a300b2" was already used`
 * while the user was flinging.
 *
 * So these cases pin the page filtering, not a key that happens to be unique: a
 * key patched with an index would leave the user looking at the same title twice,
 * which is the half of the defect that is actually visible.
 */
class BrowsePagingSourceTest {

    private val backend = RecordingBackendApi()
    private val repository = BackendBrowseRepository(supabase = FakeAccountApi(SESSION), backend = backend)

    // --- the two ways one key arrives twice -----------------------------------

    @Test
    fun `a title the server sends on two pages is handed to the grid once`() = runTest {
        backend.answerBrowseTitles(
            response("a", "b", hasMore = true),
            response("b", "c", hasMore = false),
        )
        val subject = source()

        val first = resultOf(subject.load(refresh()))
        val second = resultOf(subject.load(append(key = 1)))

        assertEquals(listOf("a", "b"), first.data.map { it.itemId })
        assertEquals(listOf("c"), second.data.map { it.itemId })
        val keys = (first.data + second.data).map { it.lazyKey() }
        assertEquals("the grid key is what the crash was about", keys.size, keys.toSet().size)
    }

    @Test
    fun `a title both fan-out halves return is handed out once`() = runTest {
        // "all" asks the movie and the series endpoint in that order, and the
        // merge concatenates them, so a title in both arrives twice in one page.
        backend.answerBrowseTitles(response("a"), response("a"))

        val page = resultOf(source(type = "all").load(refresh()))

        assertEquals(listOf("a"), page.data.map { it.itemId })
    }

    @Test
    fun `the fan-out order survives the filter`() = runTest {
        backend.answerBrowseTitles(response("shared", "movie-only"), response("shared", "series-only"))

        val page = resultOf(source(type = "all").load(refresh()))

        assertEquals(listOf("shared", "movie-only", "series-only"), page.data.map { it.itemId })
    }

    // --- the filtering must not shorten the list ------------------------------

    @Test
    fun `a page that repeats the previous one still offers the next page`() = runTest {
        backend.answerBrowseTitles(
            response("a", "b", hasMore = true),
            response("a", "b", hasMore = true),
        )
        val subject = source()

        resultOf(subject.load(refresh()))
        val second = resultOf(subject.load(append(key = 1)))

        assertEquals(emptyList<CatalogItem>(), second.data)
        assertEquals("an empty page must not end the list", 2, second.nextKey)
    }

    // --- the state is per generation ------------------------------------------

    @Test
    fun `a new source remembers nothing`() = runTest {
        backend.answerBrowseTitles(response("a", "b"), response("a", "b"))
        resultOf(source().load(refresh()))

        val fresh = resultOf(source().load(refresh()))

        assertEquals(listOf("a", "b"), fresh.data.map { it.itemId })
    }

    // --- failures ------------------------------------------------------------

    @Test
    fun `a refusal is a page error rather than a thrown exception`() = runTest {
        val signedOut = BackendBrowseRepository(supabase = FakeAccountApi(session = null), backend = backend)

        val result = BrowsePagingSource(
            repository = signedOut,
            type = "movie",
            genre = null,
            sort = "popularity",
        ).load(refresh())

        assertTrue("expected LoadResult.Error, got $result", result is PagingSource.LoadResult.Error)
    }

    @Test
    fun `cancellation escapes rather than becoming a page error`() = runTest {
        val cancelling = object : RecordingBackendApi() {
            override suspend fun browseTitles(
                accessToken: String,
                type: String,
                genre: String?,
                sort: String,
                page: Int,
            ): BrowseTitlesResponse = throw CancellationException("cancelled")
        }
        val subject = BrowsePagingSource(
            repository = BackendBrowseRepository(supabase = FakeAccountApi(SESSION), backend = cancelling),
            type = "movie",
            genre = null,
            sort = "popularity",
        )

        val thrown = runCatching { subject.load(refresh()) }.exceptionOrNull()

        assertTrue("expected the cancellation to escape, got $thrown", thrown is CancellationException)
    }

    // --- fixtures -------------------------------------------------------------

    private fun source(type: String = "movie") = BrowsePagingSource(
        repository = repository,
        type = type,
        genre = null,
        sort = "popularity",
    )

    private fun refresh(key: Int? = null, loadSize: Int = 60): PagingSource.LoadParams<Int> =
        PagingSource.LoadParams.Refresh(key = key, loadSize = loadSize, placeholdersEnabled = false)

    private fun append(key: Int, loadSize: Int = 60): PagingSource.LoadParams<Int> =
        PagingSource.LoadParams.Append(key = key, loadSize = loadSize, placeholdersEnabled = false)

    private fun resultOf(
        result: PagingSource.LoadResult<Int, CatalogItem>,
    ): PagingSource.LoadResult.Page<Int, CatalogItem> {
        assertTrue("expected a page but got $result", result is PagingSource.LoadResult.Page)
        return result as PagingSource.LoadResult.Page
    }

    private fun response(vararg ids: String, hasMore: Boolean = false) = BrowseTitlesResponse(
        items = ids.map { card(it) },
        total = ids.size,
        hasMore = hasMore,
    )

    private fun card(id: String) = ClientMediaCard(
        itemId = id,
        mediaType = "movie",
        title = "Title $id",
        overview = null,
        year = 2026,
        releaseDate = "2026-01-05",
        rating = 7.5,
        maturityRating = null,
        genres = listOf("Drama"),
        runtimeSeconds = null,
        images = ClientImages(
            artwork = ResponsiveImageSet(
                low = "https://img.test/$id.jpg",
                medium = "https://img.test/$id.jpg",
                high = "https://img.test/$id.jpg",
            ),
            logo = ResponsiveImageSet(low = null, medium = null, high = null),
            still = ResponsiveImageSet(low = null, medium = null, high = null),
        ),
        progress = null,
        parent = null,
    )

    private companion object {
        val SESSION = Session(
            accessToken = "token-1",
            refreshToken = "refresh-1",
            expiresAtEpochSec = null,
            userId = "user-1",
            email = null,
            anonymous = false,
        )
    }
}
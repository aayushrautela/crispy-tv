package com.crispy.tv.discover

import com.crispy.tv.accounts.RecordingBackendApi
import com.crispy.tv.backend.BrowseTitlesResponse
import com.crispy.tv.backend.ClientImages
import com.crispy.tv.backend.ClientMediaCard
import com.crispy.tv.accounts.Session
import com.crispy.tv.domain.browse.BROWSE_MAX_PAGES
import com.crispy.tv.images.ResponsiveImageSet
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `BackendBrowseRepository` is the first `:app` class that holds two ports and
 * nothing else, so this suite is what the split was for: it is constructible
 * with two fakes, with no `Context`, no `SharedPreferences` and no keystore.
 *
 * What is pinned here is only what this class owns. The fan-out, the page cap
 * and the merge rule belong to `:core-domain` (`planBrowseRequests`,
 * `browseTypeFanOut`, `mergeBrowsePage`) and are tested there, so the cases
 * below assert the *result* of those rules rather than restating them.
 */
class BackendBrowseRepositoryTest {

    private val backend = RecordingBackendApi()
    private val account = FakeAccountApi(SESSION)
    private val repository = BackendBrowseRepository(supabase = account, backend = backend)

    // --- the empty plan returns before anything is asked ---------------------

    @Test
    fun `a page below zero is answered from nothing at all`() = runTest {
        val page = repository.browsePage(type = "movie", page = -1)

        assertEquals(emptyList<Any>(), page.items)
        assertEquals(false, page.hasMore)
    }

    @Test
    fun `a page at the cap is answered from nothing at all`() = runTest {
        val page = repository.browsePage(type = "movie", page = BROWSE_MAX_PAGES)

        assertEquals(emptyList<Any>(), page.items)
        assertEquals(false, page.hasMore)
    }

    @Test
    fun `a plan that is empty never asks who is signed in`() = runTest {
        repository.browsePage(type = "movie", page = -1)

        assertEquals(0, account.ensureValidSessionCalls)
    }

    // --- the sign-in requirement --------------------------------------------

    @Test
    fun `no session is refused rather than answered empty`() = runTest {
        val signedOut = BackendBrowseRepository(
            supabase = FakeAccountApi(session = null),
            backend = backend,
        )

        val failure = runCatching { signedOut.browsePage(type = "movie") }.exceptionOrNull()

        assertTrue(
            "expected BrowseRequiredSignInException, got $failure",
            failure is BrowseRequiredSignInException,
        )
    }

    @Test
    fun `the refusal names the action rather than the cause`() = runTest {
        val signedOut = BackendBrowseRepository(
            supabase = FakeAccountApi(session = null),
            backend = backend,
        )

        val failure = runCatching { signedOut.browsePage(type = "movie") }.exceptionOrNull()

        assertEquals("Sign in to browse titles.", failure?.message)
    }

    // --- the mapping the class owns -----------------------------------------

    @Test
    fun `a card with no artwork is dropped rather than rendered blank`() = runTest {
        backend.answerBrowseTitles(BrowseTitlesResponse(items = listOf(card(id = "kept"), card(id = "no-art", artwork = null)), total = 2, hasMore = false))

        val page = repository.browsePage(type = "movie")

        assertEquals(listOf("kept"), page.items.map { it.itemId })
    }

    @Test
    fun `hasMore is taken from the merged page and not invented`() = runTest {
        backend.answerBrowseTitles(BrowseTitlesResponse(items = emptyList(), total = 0, hasMore = true))

        val page = repository.browsePage(type = "movie")

        assertEquals(true, page.hasMore)
    }

    private fun card(id: String, artwork: String? = "https://img.test/$id.jpg") = ClientMediaCard(
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
            artwork = ResponsiveImageSet(low = artwork, medium = artwork, high = artwork),
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

package com.crispy.tv.search

import com.crispy.tv.accounts.ActiveProfileStore
import com.crispy.tv.accounts.RecordingBackendApi
import com.crispy.tv.accounts.Session
import com.crispy.tv.backend.PersonSearchResultItem
import com.crispy.tv.backend.SearchResultsResponse
import com.crispy.tv.backend.SearchSuggestionsResponse
import com.crispy.tv.discover.FakeAccountApi
import com.crispy.tv.testing.FakeKeyValueStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the four rules [SearchViewModel] is actually responsible for: the token
 * guard that drops a stale response, the debounce that collapses a burst of
 * keystrokes into one request, the re-submit guard that issues no request at all,
 * and that the language tag the platform supplies is what reaches the wire.
 *
 * The repositories are **final classes, not interfaces**, so they are not mocked.
 * The viewmodel is handed the real [BackendSearchRepository] and
 * [AiSearchRepository] over [FakeAccountApi] and [RecordingBackendApi], which tests
 * both layers at once and keeps the port count down. The limitation that follows
 * is that a final class has no seam to fail on demand, so the failure paths below
 * are reached the way production reaches them — a blank session, no selected
 * profile — rather than by making a repository throw.
 *
 * **Both dispatchers come from this test's own scheduler.** `viewModelScope` needs
 * `Dispatchers.Main` and the request hop uses the injected `ioDispatcher`.
 * Installing Main from a `@Before` with its own `UnconfinedTestDispatcher()` gives
 * it a *different* scheduler from the one `advanceUntilIdle` drives, and because
 * `Dispatchers.IO` is a real thread pool the `withContext` suspends past an
 * unconfined main — so the request is still in flight when the assertion runs.
 * That was measured, not guessed: 13 of 17 tests failed with a null
 * `statusMessage` and an empty call list until the helper below built both
 * dispatchers from `testScheduler`. Unconfined makes the request complete inside
 * the call, and a shared scheduler still gives `delay` a clock to advance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val session = Session(
        accessToken = "token-1",
        refreshToken = "refresh-1",
        expiresAtEpochSec = null,
        userId = "user-1",
        email = "a@example.com",
        anonymous = false,
    )

    private val account = FakeAccountApi(session)
    private val backend = RecordingBackendApi()
    private val history = FakeSearchHistoryStore()

    @After
    fun restoreMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun profileStore(profileId: String?): ActiveProfileStore {
        val store = FakeKeyValueStore()
        store.putString("active_profile_id:user-1", profileId.orEmpty())
        return ActiveProfileStore(store)
    }

    private fun TestScope.viewModel(
        accountApi: FakeAccountApi = account,
        profileId: String? = "profile-1",
        languageTag: String = "en-GB",
    ): SearchViewModel {
        val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        return SearchViewModel(
            searchRepository = BackendSearchRepository(accountApi, backend),
            aiSearchRepository = AiSearchRepository(accountApi, profileStore(profileId), backend),
            searchHistoryStore = history,
            languageTagProvider = { languageTag },
            ioDispatcher = dispatcher,
        )
    }

    /**
     * A response with a populated `people` bucket, chosen because a
     * [PersonSearchResultItem] maps to a catalog item from its own fields — no
     * `ClientMediaCard` with artwork has to be built to observe a result.
     */
    private fun results(query: String, people: List<String> = emptyList()) = SearchResultsResponse(
        query = query,
        movies = emptyList(),
        series = emptyList(),
        people = people.map {
            PersonSearchResultItem(
                kind = "person",
                personId = "person-$it",
                name = it,
                knownForDepartment = null,
                profileUrl = null,
                knownForTitles = emptyList(),
            )
        },
    )

    // --- the standard search ---------------------------------------------------------

    @Test
    fun `a standard search publishes the mapped people and clears loading`() = runTest {
        backend.answerSearchTitles(results("matrix", people = listOf("Keanu")))

        val model = viewModel()
        model.submitSearch("matrix")

        val state = model.uiState.value
        assertEquals("matrix", state.executedQuery)
        assertEquals(listOf("Keanu"), state.resultBuckets.people.map { it.title })
        assertFalse(state.isLoading)
        assertNull(state.statusMessage)
        assertEquals(1, backend.searchTitlesCalls.size)
        assertEquals("matrix", backend.searchTitlesCalls[0].query)
    }

    @Test
    fun `a failure that names itself surfaces its own message`() = runTest {
        // The viewmodel publishes `it.message ?: "Search is unavailable right now."`,
        // so a test that only ever sees the generic string cannot tell the two
        // halves apart. This is the arm that pins the first half.
        backend.failSearchTitlesWith(IllegalStateException("The backend is down."))

        val model = viewModel()
        model.submitSearch("matrix")

        val state = model.uiState.value
        assertEquals("The backend is down.", state.statusMessage)
        assertTrue(state.resultBuckets.people.isEmpty())
        assertFalse(state.isLoading)
        assertEquals(1, backend.searchTitlesCalls.size)
    }

    @Test
    fun `a failure with no message of its own falls back to the generic sentence`() = runTest {
        // The other half. Without this case the `?:` above is untested, and a
        // mutation that dropped the throwable's message would pass every test.
        backend.failSearchTitlesWith(IllegalStateException())

        val model = viewModel()
        model.submitSearch("matrix")

        val state = model.uiState.value
        assertEquals("Search is unavailable right now.", state.statusMessage)
        assertFalse(state.isLoading)
    }

    @Test
    fun `the query is trimmed before it reaches the wire`() = runTest {
        backend.answerSearchTitles(results("matrix"))

        viewModel().submitSearch("  matrix  ")

        assertEquals("matrix", backend.searchTitlesCalls[0].query)
    }

    @Test
    fun `a submitted query is recorded in history and published in the state`() = runTest {
        backend.answerSearchTitles(results("matrix"))

        val model = viewModel()
        model.submitSearch("matrix")

        assertEquals(listOf("matrix"), history.recordCalls)
        assertEquals(listOf("matrix"), model.uiState.value.recentSearches)
    }

    // --- the re-submit guard ---------------------------------------------------------

    @Test
    fun `resubmitting the same query in the same mode issues no second request`() = runTest {
        backend.answerSearchTitles(results("matrix"))

        val model = viewModel()
        model.submitSearch("matrix")
        model.submitSearch("matrix")

        assertEquals(1, backend.searchTitlesCalls.size)
    }

    @Test
    fun `resubmitting the same query in a different mode does issue a second request`() = runTest {
        backend.answerSearchTitles(results("matrix"))
        backend.answerSearchAiTitles(results("matrix"))

        val model = viewModel()
        model.submitSearch("matrix")
        model.submitAiSearch("matrix")

        assertEquals(1, backend.searchTitlesCalls.size)
        assertEquals(1, backend.searchAiTitlesCalls.size)
    }

    // --- the language tag, which is the point of the boundary change -----------------

    @Test
    fun `the language tag the platform supplies is what the AI search sends`() = runTest {
        backend.answerSearchAiTitles(results("matrix"))

        viewModel(languageTag = "pt-BR").submitAiSearch("matrix")

        assertEquals("pt-BR", backend.searchAiTitlesCalls[0].locale)
    }

    @Test
    fun `a standard search never reaches the AI endpoint`() = runTest {
        backend.answerSearchTitles(results("matrix"))

        viewModel(languageTag = "pt-BR").submitSearch("matrix")

        assertEquals(0, backend.searchAiTitlesCalls.size)
    }

    // --- the unauthenticated and unselected paths ------------------------------------

    @Test
    fun `no session surfaces a sign-in message instead of a result`() = runTest {
        val anonymous = FakeAccountApi(null)
        backend.answerSearchTitles(results("matrix"))

        val model = viewModel(accountApi = anonymous)
        model.submitSearch("matrix")

        assertEquals("Sign in to search.", model.uiState.value.statusMessage)
        assertEquals(0, backend.searchTitlesCalls.size)
    }

    @Test
    fun `AI search with no selected profile asks for a profile rather than searching`() = runTest {
        backend.answerSearchAiTitles(results("matrix"))

        val model = viewModel(profileId = null)
        model.submitAiSearch("matrix")

        assertEquals("Select a profile to use AI search.", model.uiState.value.statusMessage)
        assertEquals(0, backend.searchAiTitlesCalls.size)
    }

    // --- genre -----------------------------------------------------------------------

    @Test
    fun `selecting a genre searches by genre and marks the selection`() = runTest {
        backend.answerSearchTitlesByGenre(results("action", people = listOf("Someone")))

        val model = viewModel()
        model.selectGenre(SearchGenreSuggestion.ACTION)

        val state = model.uiState.value
        assertEquals(SearchGenreSuggestion.ACTION, state.selectedGenre)
        assertEquals("Action", backend.searchTitlesByGenreCalls[0].genre)
        assertEquals(0, backend.searchTitlesCalls.size)
    }

    @Test
    fun `reselecting the same genre issues no second request`() = runTest {
        backend.answerSearchTitlesByGenre(results("action"))

        val model = viewModel()
        model.selectGenre(SearchGenreSuggestion.ACTION)
        model.selectGenre(SearchGenreSuggestion.ACTION)

        assertEquals(1, backend.searchTitlesByGenreCalls.size)
    }

    // --- history ---------------------------------------------------------------------

    @Test
    fun `removing a recent search republishes what the store returned`() = runTest {
        history.seed(listOf("matrix", "alien"))

        val model = viewModel()
        model.removeRecentSearch("matrix")

        assertEquals(listOf("alien"), model.uiState.value.recentSearches)
        assertEquals(listOf("matrix"), history.removeCalls)
    }

    @Test
    fun `clearing recent searches republishes an empty list`() = runTest {
        history.seed(listOf("matrix"))

        val model = viewModel()
        model.clearRecentSearches()

        assertEquals(emptyList<String>(), model.uiState.value.recentSearches)
        assertEquals(1, history.clearCalls)
    }

    @Test
    fun `a viewmodel starts from the stored history`() = runTest {
        history.seed(listOf("matrix"))

        val model = viewModel()

        assertEquals(listOf("matrix"), model.uiState.value.recentSearches)
        assertEquals(1, history.loadCalls)
    }

    // --- clearing --------------------------------------------------------------------

    @Test
    fun `clearing resets the results and republishes the history`() = runTest {
        backend.answerSearchTitles(results("matrix"))
        history.seed(listOf("matrix"))

        val model = viewModel()
        model.submitSearch("matrix")
        model.clearSearch()

        val state = model.uiState.value
        assertEquals("", state.executedQuery)
        assertTrue(state.resultBuckets.isEmpty)
        assertFalse(state.hasActiveResults)
        assertEquals(listOf("matrix"), state.recentSearches)
    }

    // --- the suggestion debounce, on the virtual clock -------------------------------

    @Test
    fun `a burst of keystrokes asks for suggestions once`() = runTest {
        backend.answerSearchSuggestions(SearchSuggestionsResponse(listOf("matrix", "matinee")))

        val model = viewModel()
        model.updateQuery("m")
        model.updateQuery("ma")
        model.updateQuery("mat")
        advanceUntilIdle()

        assertEquals(1, backend.searchSuggestionsCalls.size)
        assertEquals("mat", backend.searchSuggestionsCalls[0].query)
        assertEquals(listOf("matrix", "matinee"), model.uiState.value.suggestions)
    }

    @Test
    fun `a blank query adds no further suggestions request`() = runTest {
        backend.answerSearchSuggestions(SearchSuggestionsResponse(listOf("matrix")))

        val model = viewModel()
        model.updateQuery("matrix")
        advanceUntilIdle()
        model.updateQuery("   ")
        advanceUntilIdle()

        assertEquals(1, backend.searchSuggestionsCalls.size)
    }

    @Test
    fun `a stale search response never lands`() = runTest {
        // The first search is held open at the double, so the second one is
        // issued while the first is still in flight. That is the only way the
        // token guard can mismatch: on an unconfined dispatcher with a
        // synchronous answer each response lands inside the call that issued it
        // and the guard is never reached.
        backend.answerSearchTitles(
            results(query = "first", people = listOf("Keanu")),
            results(query = "second", people = listOf("Carrie")),
        )
        backend.holdSearchTitles()

        val model = viewModel()
        model.submitSearch("first")
        // The held call has been recorded but has not answered yet.
        assertEquals(1, backend.searchTitlesCalls.size)
        assertTrue("the first search is still loading", model.uiState.value.isLoading)

        model.submitSearch("second")
        advanceUntilIdle()
        backend.releaseSearchTitles()
        advanceUntilIdle()

        // The second response is the one on screen; the first was dropped.
        assertEquals("second", model.uiState.value.executedQuery)
        assertEquals(listOf("Carrie"), model.uiState.value.resultBuckets.people.map { it.title })
        assertFalse("the stale response did not reopen the spinner", model.uiState.value.isLoading)
        // This is where a dropped guard actually shows. A cancelled coroutine
        // unwinds through `runCatching`, which catches CancellationException and
        // turns it into a fallback payload -- so the stale request publishes its
        // *message*, not its results. The people list cannot see this; the
        // status line can, and must stay clean.
        assertNull("no cancellation message leaked into the status", model.uiState.value.statusMessage)
    }

    @Test
    fun `a stale suggestion response never lands`() = runTest {
        backend.answerSearchSuggestions(
            SearchSuggestionsResponse(listOf("matrix")),
            SearchSuggestionsResponse(listOf("matinee")),
        )

        val model = viewModel()
        model.updateQuery("mat")
        advanceUntilIdle()
        model.updateQuery("mati")
        advanceUntilIdle()

        assertEquals(listOf("matinee"), model.uiState.value.suggestions)
    }
}

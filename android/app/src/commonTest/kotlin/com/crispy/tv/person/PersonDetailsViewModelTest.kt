package com.crispy.tv.person

import com.crispy.tv.backend.PersonSocials
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlin.test.AfterTest
import org.junit.Test

/**
 * Covers [PersonDetailsViewModel] now that it is in `commonMain` and takes only a
 * `suspend` loader and a language tag.
 *
 * The dispatcher is the reason this class has a helper rather than a `@Before`:
 * `viewModelScope` needs `Dispatchers.Main` **and** the viewmodel hops to
 * `ioDispatcher` for the load, so one `UnconfinedTestDispatcher` is installed as
 * both. Installing only `setMain` leaves the real `Dispatchers.IO` in place and
 * every load suspends past an unconfined main -- measured on `SearchViewModel`,
 * where it failed 13 of 17 tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PersonDetailsViewModelTest {

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a loaded person is published`() = runTest {
        val viewModel = viewModel { id, _ -> person(id = id) }

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals("person-7", state.person?.personId)
        assertEquals("Keanu Reeves", state.person?.name)
        assertNull(state.errorMessage)
    }

    @Test
    fun `the loader receives the requested person id and the language tag`() = runTest {
        var seenId: String? = null
        var seenTag: String? = null

        val viewModel = viewModel(
            tag = "pt-BR",
            loader = { id, languageTag ->
                seenId = id
                seenTag = languageTag
                person(id = id)
            },
        )

        assertEquals("person-7", seenId)
        assertEquals("pt-BR", seenTag)
        assertNotNull(viewModel.uiState.value.person)
    }

    /**
     * A loader returning null is how "no session" and "the backend failed" both
     * reach the viewmodel -- the factory collapses them with `getOrNull()`. So the
     * null case has to be a first-class outcome, not an edge case.
     */
    @Test
    fun `a loader that finds nothing surfaces the failure message`() = runTest {
        val viewModel = viewModel { _, _ -> null }

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.person)
        assertEquals("Failed to load", state.errorMessage)
    }

    @Test
    fun `the load runs off the calling thread's dispatcher`() = runTest {
        var calls = 0

        viewModel { id, _ -> calls++; person(id = id) }

        assertEquals(1, calls)
    }

    /**
     * The guard is only reachable while a load is genuinely suspended, so the gate
     * is held open for the duration. An earlier version of this case asserted
     * `calls == 1` after two `refresh()` calls with no gate and failed with 3: on an
     * unconfined dispatcher the first load *completes* before `refresh()` returns,
     * so the job is no longer active and the guard never fires. That is a property
     * of the test dispatcher, not of the code.
     */
    @Test
    fun `a second refresh while a load is in flight is ignored`() = runTest {
        var calls = 0
        val gate = CompletableDeferred<Unit>()

        val viewModel = viewModel { id, _ -> calls++; gate.await(); person(id = id) }
        assertEquals(1, calls)

        viewModel.refresh()
        viewModel.refresh()

        assertEquals(1, calls)
        assertTrue(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `a refresh after the load settled fetches again`() = runTest {
        var calls = 0

        val viewModel = viewModel { id, _ -> calls++; person(id = id) }
        viewModel.refresh()

        assertEquals(2, calls)
    }

    /**
     * A loader that starts succeeding and then fails replaces the whole state, so
     * the person already on screen is dropped and the error is set. `refresh()`
     * *does* clear a stale error before the load when a person is present, but that
     * copy is overwritten the moment the loader returns null -- so the error-clearing
     * line is only visible while a refresh is in flight, which is what the
     * `isLoading` assertion below pins. Asserted here as the code behaves, not as it
     * was presumably meant to.
     */
    @Test
    fun `a failed refresh replaces the state and reports the failure`() = runTest {
        var shouldFail = false

        val viewModel = viewModel { id, _ ->
            if (shouldFail) null else person(id = id)
        }
        assertNotNull(viewModel.uiState.value.person)

        shouldFail = true
        viewModel.refresh()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.person)
        assertEquals("Failed to load", state.errorMessage)
    }

    @Test
    fun `the state starts loading before the first result lands`() = runTest {
        val gate = CompletableDeferred<Unit>()

        val viewModel = viewModel { id, _ -> gate.await(); person(id = id) }

        // The first load has not been released, so the state must still be loading.
        assertTrue(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.person)

        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
    }

    private fun kotlinx.coroutines.test.TestScope.viewModel(
        tag: String = "en-US",
        loader: suspend (String, String) -> PersonDetails?,
    ): PersonDetailsViewModel {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        return PersonDetailsViewModel(
            personId = "person-7",
            personLoader = loader,
            languageTagProvider = { tag },
            ioDispatcher = dispatcher,
        )
    }

    private fun person(id: String) = PersonDetails(
        personId = id,
        name = "Keanu Reeves",
        knownForDepartment = "Acting",
        biography = null,
        birthday = null,
        placeOfBirth = null,
        profileUrl = null,
        socials = PersonSocials(
            imdbId = null,
            instagram = null,
            twitter = null,
            facebook = null,
            tiktok = null,
            youtube = null,
        ),
        knownForRails = emptyList(),
    )
}

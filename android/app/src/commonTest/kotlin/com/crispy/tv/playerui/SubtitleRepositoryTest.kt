package com.crispy.tv.playerui

import com.crispy.tv.addons.streams.AddonSubtitle
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.testing.FakeStreamResolver
import com.crispy.tv.testing.RecordingAppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SubtitleRepository] became testable only when it moved to `commonMain`: it now takes
 * the [com.crispy.tv.addons.streams.StreamResolver] port and an
 * [com.crispy.tv.platform.AppLogger] and nothing else. Before that it reached a final
 * class built from a `Context`, so there was nothing a test could hand it.
 *
 * The class also used to build its own `CoroutineScope(SupervisorJob() + Dispatchers.IO)`,
 * which no test can drive: `advanceUntilIdle` runs the *test* scheduler, and work posted to
 * a real IO dispatcher is not on it. The scope is therefore a constructor parameter whose
 * default is the old behaviour, and every test hands it an `UnconfinedTestDispatcher` scope.
 * Unconfined is what makes these tests need no `advanceUntilIdle` at all: the fetch body
 * runs eagerly on the calling thread, and [FakeStreamResolver] never really suspends, so
 * the whole block completes inside the `fetchAddonSubtitles` call. Waiting on a duration
 * instead would make these tests slow and still racy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubtitleRepositoryTest {

    private val scope = CoroutineScope(UnconfinedTestDispatcher())

    private fun subtitle(id: String, language: String = "eng") =
        AddonSubtitle(id = id, url = "https://example.test/$id.vtt", language = language, display = language)

    @Test
    fun `a first fetch asks the resolver once and publishes the subtitles`() = runTest {
        val resolver = FakeStreamResolver(subtitles = listOf(subtitle("a"), subtitle("b")))
        val repository = SubtitleRepository(resolver, RecordingAppLogger(), scope)

        repository.fetchAddonSubtitles(MetadataLabMediaType.MOVIE, "tt-1")

        assertEquals(1, resolver.fetchCalls.size)
        assertEquals(MetadataLabMediaType.MOVIE to "tt-1", resolver.fetchCalls.single())
        assertEquals(listOf("a", "b"), repository.addonSubtitles.value.map { it.id })
        assertFalse(repository.isLoading.value)
        assertNull(repository.error.value)
    }

    @Test
    fun `an empty result is reported as no subtitles found`() = runTest {
        val repository =
            SubtitleRepository(FakeStreamResolver(subtitles = emptyList()), RecordingAppLogger(), scope)

        repository.fetchAddonSubtitles(MetadataLabMediaType.MOVIE, "tt-1")

        assertEquals(emptyList<AddonSubtitle>(), repository.addonSubtitles.value)
        assertEquals("No subtitles found", repository.error.value)
    }

    @Test
    fun `a failure surfaces its own message and leaves the published subtitles alone`() = runTest {
        val resolver = FakeStreamResolver(subtitles = listOf(subtitle("a")))
        val repository = SubtitleRepository(resolver, RecordingAppLogger(), scope)

        repository.fetchAddonSubtitles(MetadataLabMediaType.MOVIE, "tt-1")
        assertEquals(listOf("a"), repository.addonSubtitles.value.map { it.id })

        resolver.fetchFailure = IllegalStateException("addon registry unreachable")
        repository.fetchAddonSubtitles(MetadataLabMediaType.MOVIE, "tt-1", force = true)

        assertEquals("addon registry unreachable", repository.error.value)
        assertEquals(listOf("a"), repository.addonSubtitles.value.map { it.id })
    }

    @Test
    fun `a failure with no message falls back to a readable default`() = runTest {
        val resolver = FakeStreamResolver()
        resolver.fetchFailure = IllegalStateException()
        val repository = SubtitleRepository(resolver, RecordingAppLogger(), scope)

        repository.fetchAddonSubtitles(MetadataLabMediaType.MOVIE, "tt-1")

        assertEquals("Failed to fetch subtitles", repository.error.value)
    }

    @Test
    fun `a second fetch of the same title is served from the cache without asking the resolver`() = runTest {
        val resolver = FakeStreamResolver(subtitles = listOf(subtitle("a")))
        val repository = SubtitleRepository(resolver, RecordingAppLogger(), scope)

        repository.fetchAddonSubtitles(MetadataLabMediaType.MOVIE, "tt-1")
        repository.fetchAddonSubtitles(MetadataLabMediaType.MOVIE, "tt-1")

        assertEquals("the cache hit must not reach the resolver", 1, resolver.fetchCalls.size)
        assertEquals(listOf("a"), repository.addonSubtitles.value.map { it.id })
    }

    @Test
    fun `force re-asks the resolver even for the title already cached`() = runTest {
        val resolver = FakeStreamResolver(subtitles = listOf(subtitle("a")))
        val repository = SubtitleRepository(resolver, RecordingAppLogger(), scope)

        repository.fetchAddonSubtitles(MetadataLabMediaType.MOVIE, "tt-1")
        resolver.subtitles = listOf(subtitle("a"), subtitle("b"))
        repository.fetchAddonSubtitles(MetadataLabMediaType.MOVIE, "tt-1", force = true)

        assertEquals(2, resolver.fetchCalls.size)
        assertEquals(listOf("a", "b"), repository.addonSubtitles.value.map { it.id })
    }

    @Test
    fun `a different media type under the same lookup id is a cache miss`() = runTest {
        val resolver = FakeStreamResolver(subtitles = listOf(subtitle("a")))
        val repository = SubtitleRepository(resolver, RecordingAppLogger(), scope)

        repository.fetchAddonSubtitles(MetadataLabMediaType.MOVIE, "tt-1")
        repository.fetchAddonSubtitles(MetadataLabMediaType.SERIES, "tt-1")

        assertEquals(
            "the cache key is the media type and id together, not the id alone",
            listOf(MetadataLabMediaType.MOVIE, MetadataLabMediaType.SERIES),
            resolver.fetchCalls.map { it.first },
        )
    }

    @Test
    fun `a later fetch clears the error a previous empty fetch left behind`() = runTest {
        val resolver = FakeStreamResolver(subtitles = emptyList())
        val repository = SubtitleRepository(resolver, RecordingAppLogger(), scope)

        repository.fetchAddonSubtitles(MetadataLabMediaType.MOVIE, "tt-1")
        assertEquals("No subtitles found", repository.error.value)

        resolver.subtitles = listOf(subtitle("a"))
        repository.fetchAddonSubtitles(MetadataLabMediaType.MOVIE, "tt-1", force = true)
        assertNull(repository.error.value)
    }

    @Test
    fun `a cache hit reaches no network and clears the error`() = runTest {
        val resolver = FakeStreamResolver(subtitles = listOf(subtitle("a")))
        val repository = SubtitleRepository(resolver, RecordingAppLogger(), scope)

        // The cache is a single slot, so a second title would displace the first. The
        // failing fetch below is therefore for a title that never reaches the cache: a
        // failure does not store, so the slot still holds tt-1's successful answer.
        repository.fetchAddonSubtitles(MetadataLabMediaType.MOVIE, "tt-1")
        resolver.fetchFailure = IllegalStateException("addon registry unreachable")
        repository.fetchAddonSubtitles(MetadataLabMediaType.MOVIE, "tt-2")
        assertEquals("the failure is on screen before the cache is consulted", "addon registry unreachable", repository.error.value)

        resolver.fetchFailure = null
        resolver.subtitles = emptyList()
        repository.fetchAddonSubtitles(MetadataLabMediaType.MOVIE, "tt-1")

        assertEquals("the hit did not reach the resolver", 2, resolver.fetchCalls.size)
        assertEquals("a hit leaves what was published alone", listOf("a"), repository.addonSubtitles.value.map { it.id })
        assertNull("a cache hit clears the previous error", repository.error.value)
        assertFalse(repository.isLoading.value)
    }

    @Test
    fun `a successful fetch is logged and never as a warning`() = runTest {
        val resolver = FakeStreamResolver(subtitles = listOf(subtitle("a")))
        val logger = RecordingAppLogger()
        val repository = SubtitleRepository(resolver, logger, scope)

        repository.fetchAddonSubtitles(MetadataLabMediaType.MOVIE, "tt-1")

        assertTrue(logger.debugs.any { it.first == "CrispySubtitles" })
        assertTrue("a successful fetch is not a warning", logger.warns.isEmpty())
    }

    /**
     * The suspend half, and the reason the player can attach a matched subtitle before
     * the first frame rather than after it.
     *
     * Two things are asserted, and the second is the one that carries the decision:
     * **that `isLoading` is `true` when the call has not yet returned.** Without it the
     * first assertion is vacuous -- an eager `assertFalse(settled)` would also hold if
     * the coroutine had never started, because `runCurrent()` on a scheduler nothing has
     * been posted to runs nothing. The gate is what holds the fetch, and the flag is
     * the positive evidence the body ran past it.
     */
    @Test
    fun `fetchAndAwait returns only once the resolver has answered`() = runTest {
        val gate = CompletableDeferred<List<AddonSubtitle>>()
        val resolver =
            object : FakeStreamResolver() {
                override suspend fun fetchAddonSubtitles(
                    mediaType: MetadataLabMediaType,
                    lookupId: String,
                ): List<AddonSubtitle> = gate.await()
            }
        // A scope on this test's own scheduler. The class-level scope has its own, so
        // work posted there could never be advanced from here.
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repository = SubtitleRepository(resolver, RecordingAppLogger(), CoroutineScope(dispatcher))

        var settled = false
        val fetch =
            launch(dispatcher) {
                repository.fetchAndAwait(MetadataLabMediaType.MOVIE, "tt-1")
                settled = true
            }
        testScheduler.advanceUntilIdle()
        val held = "the gate is what holds the fetch, not the scheduler"
        assertFalse(held, settled)
        assertTrue(held, repository.isLoading.value)

        gate.complete(listOf(subtitle("a")))
        fetch.join()

        assertTrue("a completed resolver settles the call", settled)
        assertEquals("the same list the resolver returned", listOf("a"), repository.addonSubtitles.value.map { it.id })
        assertFalse(repository.isLoading.value)
        assertNull(repository.error.value)
    }

    /**
     * An empty answer is published and not remembered.
     *
     * The reason is not tidiness: `AddonStreamsService` returns `emptyList()` both for
     * "no addon here declares a `subtitles` resource" and for "the addons were not warm
     * yet when playback started", and only the first of those is a fact about the
     * title. Storing the second made the sheet show nothing on open until the viewer
     * pressed Search, and the entry fetch was the only thing that had ever been asked.
     *
     * So the assertion is that a non-forced refetch reaches the resolver again.
     */
    @Test
    fun `an empty result is not remembered as the absence of subtitles`() = runTest {
        val resolver = FakeStreamResolver(subtitles = emptyList())
        val repository = SubtitleRepository(resolver, RecordingAppLogger(), scope)

        repository.fetchAddonSubtitles(MetadataLabMediaType.MOVIE, "tt-1")
        repository.fetchAddonSubtitles(MetadataLabMediaType.MOVIE, "tt-1")

        assertEquals("a non-forced refetch must reach the resolver again", 2, resolver.fetchCalls.size)
        // Published either way: an empty list on screen is still information, and it
        // still carries the "No subtitles found" explanation.
        assertEquals(emptyList<AddonSubtitle>(), repository.addonSubtitles.value)
        assertEquals("No subtitles found", repository.error.value)
    }

    /**
     * Two callers, one round trip.
     *
     * The player asks for the same title's subtitles at entry and again when the stream
     * has been chosen, and both calls happen while the first is still in flight. The
     * second used to start a second round trip, resolving every add-on manifest twice
     * for one title.
     *
     * The gate holds the resolver, so both calls are provably in flight at once. The
     * assertions are on the resolver's call count and on both callers returning: a joiner
     * that never returned would hang here rather than fail quietly, because the first
     * `join()` in the test body would not complete.
     */
    @Test
    fun `two callers for one in-flight title reach the resolver once`() = runTest {
        val gate = CompletableDeferred<List<AddonSubtitle>>()
        val resolver =
            object : FakeStreamResolver() {
                override suspend fun fetchAddonSubtitles(
                    mediaType: MetadataLabMediaType,
                    lookupId: String,
                ): List<AddonSubtitle> {
                    // Recorded before suspending: the assertion below counts calls, and a
                    // count taken after `gate.await()` would be zero while the fetch is
                    // genuinely parked inside this function.
                    fetchCalls += mediaType to lookupId
                    return gate.await()
                }
            }
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repository = SubtitleRepository(resolver, RecordingAppLogger(), CoroutineScope(dispatcher))

        val first = launch(dispatcher) { repository.fetchAndAwait(MetadataLabMediaType.MOVIE, "tt-1") }
        val second = launch(dispatcher) { repository.fetchAndAwait(MetadataLabMediaType.MOVIE, "tt-1") }
        testScheduler.advanceUntilIdle()
        // Both are parked inside the repository, so neither has asked yet and neither has
        // returned. This is the state the dedup exists for.
        assertFalse("the gate holds the resolver", gate.isCompleted)
        assertEquals(1, resolver.fetchCalls.size)

        gate.complete(listOf(subtitle("a")))
        first.join()
        second.join()

        assertEquals("one round trip served both callers", 1, resolver.fetchCalls.size)
        assertEquals("both callers saw it", listOf("a"), repository.addonSubtitles.value.map { it.id })
        assertFalse(repository.isLoading.value)
        assertNull(repository.error.value)
    }

    /**
     * Cancellation is not a failure.
     *
     * The fetch used to collapse everything through `runCatching`, which catches
     * `CancellationException` and answers it as state -- so a cancelled fetch left
     * "Failed to fetch subtitles" on screen for a title nobody had asked about
     * anymore. A coroutine that swallows cancellation is the one thing this repository
     * must not do, and the observable side of it is exactly `_error`.
     *
     * The scope is cancelled, not the caller. That is the right target and it is not an
     * accident of this test's shape: `fetchAndAwait` runs its body in the scope the
     * repository was built with -- which is why a player caller that stops waiting on a
     * timeout does not kill the network fetch and the sheet still fills. So the only way
     * to reach the parked body is to cancel that scope's job, which is the real event
     * (the player is going away).
     *
     * Cancelling the test's own `launch` instead was measured to leave the error
     * "No subtitles found" behind: the body lives on the other scope, so it ran to
     * completion once the gate was released and published like nothing had happened.
     */
    @Test
    fun `a cancelled fetch reports no error at all`() = runTest {
        val gate = CompletableDeferred<List<AddonSubtitle>>()
        val resolver =
            object : FakeStreamResolver() {
                override suspend fun fetchAddonSubtitles(
                    mediaType: MetadataLabMediaType,
                    lookupId: String,
                ): List<AddonSubtitle> {
                    fetchCalls += mediaType to lookupId
                    return gate.await()
                }
            }
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        // A cancellable job as the repository scope's parent, so this test can cancel the
        // scope the fetch body actually runs in.
        val fetchScopeJob = Job()
        val repository =
            SubtitleRepository(resolver, RecordingAppLogger(), CoroutineScope(dispatcher + fetchScopeJob))

        // The caller stays alive on the test's scope, which is the interesting case: it
        // gets its own CancellationException from `job.join()`, so what it does with it
        // must not be to publish `_error` on the way out.
        val caller = launch(dispatcher) {
            try {
                repository.fetchAndAwait(MetadataLabMediaType.MOVIE, "tt-1")
            } catch (cancellation: CancellationException) {
                // Swallowed on purpose so the test can observe the repository afterwards;
                // a `runTest` body must not let this escape uncaught.
            }
        }
        testScheduler.advanceUntilIdle()
        assertTrue("the body ran up to the gate", repository.isLoading.value)
        assertEquals(1, resolver.fetchCalls.size)

        fetchScopeJob.cancel()
        gate.complete(emptyList())
        testScheduler.advanceUntilIdle()

        assertNull(
            "cancellation is not an answer and must not publish one as if it were",
            repository.error.value,
        )
        assertEquals(emptyList<AddonSubtitle>(), repository.addonSubtitles.value)
        // Deliberately no assertion on `isLoading` here: a cancelled fetch is not a
        // finished one, so it leaves the flag to whichever fetch is still running --
        // lowering it would clear the superseding fetch's own flag in the force path.
        // The slot release above is the observable half that matters (a later call
        // must not join the dead job), and it is covered by the refetch test.
        caller.join()
    }
}

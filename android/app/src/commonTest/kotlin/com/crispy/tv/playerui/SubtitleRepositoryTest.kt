package com.crispy.tv.playerui

import com.crispy.tv.addons.streams.AddonSubtitle
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.testing.FakeStreamResolver
import com.crispy.tv.testing.RecordingAppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
}

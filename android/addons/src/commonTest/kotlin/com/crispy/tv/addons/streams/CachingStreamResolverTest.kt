package com.crispy.tv.addons.streams

import com.crispy.tv.addons.lookup.StreamLookupTarget
import com.crispy.tv.platform.AppLogger
import com.crispy.tv.player.MetadataLabMediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The only member [CachingStreamResolver] adds over [StreamResolver] is caching, so
 * that is what this suite is about; the rest exists to pin the three decisions the
 * cache is built on.
 *
 * **The clock is a slot and this is the reason.** `cachedStreams`' expiry rule is a
 * comparison against the clock, so a real clock cannot distinguish "expired" from
 * "not yet" -- and a fixed clock cannot reach the boundary at all, because the rule
 * is `nowMs() > expiresAt` and the entry's expiry is stamped from the same `nowMs`.
 * [Clock] moves in whole TTLs so one case sits on each side of that comparison and
 * one sits exactly on it.
 */
class CachingStreamResolverTest {

    // ---------------------------------------------------------------- the cache

    @Test
    fun anUnseenTargetHasNoCachedEntry() = runTest {
        val resolver = fixture()

        assertNull(resolver.resolver.cachedStreams(target()))
    }

    @Test
    fun aFirstResolveIsNotCachedUntilItHasRun() = runTest {
        val f = fixture()

        assertNull(f.resolver.cachedStreams(target()), "before resolve")

        f.resolver.resolve(target(), null, null)

        assertEquals(listOf(result("prov")), f.resolver.cachedStreams(target()), "after resolve")
    }

    @Test
    fun cachedStreamsAnswersWithExactlyWhatTheLoaderReturned() = runTest {
        val f = fixture(results = listOf(result("a"), result("b")))

        val returned = f.resolver.resolve(target(), null, null)

        assertSame(returned, f.resolver.cachedStreams(target()), "the cache holds the loader's list, not a copy")
    }

    @Test
    fun aSecondResolveReplacesTheEntryAndAsksTheLoaderAgain() = runTest {
        val f = fixture()

        f.resolver.resolve(target(), null, null)
        f.loader.streams = listOf(result("later"))
        val second = f.resolver.resolve(target(), null, null)

        assertEquals(2, f.loader.loadStreamsCalls, "the cache is not consulted by resolve()")
        assertEquals(listOf(result("later")), second)
        assertEquals(listOf(result("later")), f.resolver.cachedStreams(target()))
    }

    // ---------------------------------------------------------------- the TTL

    @Test
    fun anEntryInsideItsTtlIsStillThere() = runTest {
        val f = fixture()

        f.resolver.resolve(target(), null, null)
        f.clock.nowMs = Clock.START + Clock.TTL - 1

        assertEquals(listOf(result("prov")), f.resolver.cachedStreams(target()))
    }

    @Test
    fun anEntryIsStillThereExactlyAtItsExpiryBecauseTheComparisonIsStrict() = runTest {
        val f = fixture()

        f.resolver.resolve(target(), null, null)
        f.clock.nowMs = Clock.START + Clock.TTL

        assertEquals(
            listOf(result("prov")),
            f.resolver.cachedStreams(target()),
            "`nowMs() > expiresAtEpochMs` is false on equality, so the boundary belongs to the entry",
        )
    }

    @Test
    fun anExpiredEntryIsGoneAndStaysGoneBecauseItIsRemoved() = runTest {
        val f = fixture()

        f.resolver.resolve(target(), null, null)
        f.clock.nowMs = Clock.START + Clock.TTL + 1

        assertNull(f.resolver.cachedStreams(target()), "first read past the expiry")
        assertNull(f.resolver.cachedStreams(target()), "second read: the entry was removed, not re-answered")
    }

    @Test
    fun anExpiredEntryIsEvictedFromTheMapAndNotMerelyLeftUnreadable() = runTest {
        val f = fixture()

        f.resolver.resolve(target(), null, null)
        f.resolver.resolve(StreamLookupTarget(MetadataLabMediaType.MOVIE, "tt2"), null, null)
        assertEquals(2, f.resolver.cachedEntryCount(), "two targets resolved, so two entries")

        f.clock.nowMs = Clock.START + Clock.TTL + 1
        assertNull(f.resolver.cachedStreams(target()), "past the expiry the entry is not returned")
        assertEquals(
            1,
            f.resolver.cachedEntryCount(),
            "the stale entry is gone from the map: answering null is not the same as not keeping it",
        )
    }

    @Test
    fun theTtlIsRereadOnEveryHitSoARefreshExtendsIt() = runTest {
        val f = fixture()

        f.resolver.resolve(target(), null, null)
        f.clock.nowMs = Clock.START + Clock.TTL - 1
        f.resolver.resolve(target(), null, null)
        f.clock.nowMs = Clock.START + Clock.TTL + 1

        assertEquals(
            listOf(result("prov")),
            f.resolver.cachedStreams(target()),
            "the second resolve stamped a new expiry, so the entry outlived the first TTL",
        )
    }

    // ---------------------------------------------------------------- the cache key

    @Test
    fun theCacheKeyTrimsTheLookupId() = runTest {
        val f = fixture()

        f.resolver.resolve(StreamLookupTarget(MetadataLabMediaType.MOVIE, "  tt1  "), null, null)

        assertEquals(
            listOf(result("prov")),
            f.resolver.cachedStreams(StreamLookupTarget(MetadataLabMediaType.MOVIE, "tt1")),
            "the key is trimmed, so a padded lookup id finds the entry written from the padded one",
        )
        assertEquals(1, f.loader.loadStreamsCalls)
    }

    @Test
    fun theCacheKeyLowercasesTheMediaTypeButNotTheLookupId() = runTest {
        val f = fixture()

        f.resolver.resolve(StreamLookupTarget(MetadataLabMediaType.MOVIE, "tt1"), null, null)

        assertNull(
            f.resolver.cachedStreams(StreamLookupTarget(MetadataLabMediaType.MOVIE, "TT1")),
            "RECORDED, NOT FIXED: only the media type's own enum name is lowercased, so two ids that " +
                "differ only in case are two entries and the loader is asked twice. Written down because " +
                "the first version of this case asserted a lowercase lookup id and the cache missed -- " +
                "and the failing assertion, not the code, was the wrong half.",
        )
    }

    @Test
    fun theCacheKeyDistinguishesMediaTypesThatShareALookupId() = runTest {
        val f = fixture()

        f.resolver.resolve(StreamLookupTarget(MetadataLabMediaType.MOVIE, "tt1"), null, null)

        assertNull(
            f.resolver.cachedStreams(StreamLookupTarget(MetadataLabMediaType.SERIES, "tt1")),
            "the media type is half the key, so the same id under another type is a different lookup",
        )
    }

    // ---------------------------------------------------------------- failures

    @Test
    fun aFailedResolveIsLoggedAndRethrown() = runTest {
        val boom = IllegalStateException("manifest unreachable")
        val f = fixture(failure = boom)

        val thrown = assertFailsWith<IllegalStateException> { f.resolver.resolve(target(), null, null) }

        assertSame(boom, thrown, "the loader's own exception is rethrown, not a new one")
        assertEquals(
            listOf(ErrorCall(boom)),
            f.logger.errors.map { ErrorCall(it.throwable) },
            "logger.error is called with the throwable, which is the only way the message can name a cause",
        )
    }

    @Test
    fun aFailedResolveLeavesNothingCached() = runTest {
        val f = fixture(failure = IllegalStateException("nope"))

        assertFailsWith<IllegalStateException> { f.resolver.resolve(target(), null, null) }

        assertNull(f.resolver.cachedStreams(target()), "a failure is not an empty result")
    }

    @Test
    fun cancellationIsLoggedAsAnErrorAndStillPropagates() = runTest {
        val cancelled = CancellationException("scope closed")
        val f = fixture(failure = cancelled)

        val thrown = assertFailsWith<CancellationException> { f.resolver.resolve(target(), null, null) }

        assertSame(cancelled, thrown, "catching Throwable and rethrowing preserves cancellation")
        assertEquals(
            listOf(cancelled),
            f.logger.errors.map { it.throwable },
            "RECORDED, NOT FIXED: `catch (e: Throwable)` cannot tell a cancellation from a failure, so a " +
                "scope closing mid-resolve is logged as an error. The propagation is correct; the log is not.",
        )
    }

    // ---------------------------------------------------------------- delegation

    @Test
    fun resolveHandsBothCallbacksToTheLoaderUnchanged() = runTest {
        val f = fixture()
        val onProviders: (List<StreamProviderDescriptor>) -> Unit = {}
        val onResult: (ProviderStreamsResult) -> Unit = {}

        f.resolver.resolve(target(), onProviders, onResult)

        assertSame(onProviders, f.loader.lastOnProvidersResolved, "the resolver does not wrap the callback")
        assertSame(onResult, f.loader.lastOnProviderResult)
    }

    @Test
    fun resolveAsksTheLoaderForNoPreferredProvider() = runTest {
        val f = fixture()

        f.resolver.resolve(target(), null, null)

        assertNull(
            f.loader.lastPreferredProviderId,
            "the cache decorator never expresses a preference, so the loader's own default stands",
        )
    }

    @Test
    fun resolveHandsTheLoadersLookupIdOverUntrimmed() = runTest {
        val f = fixture()

        f.resolver.resolve(StreamLookupTarget(MetadataLabMediaType.MOVIE, "  Movie-Id  "), null, null)

        assertEquals(
            "  Movie-Id  ",
            f.loader.lastLookupId,
            "the key trims and the request does not: trimming a lookup id the loader must resolve is a " +
                "behaviour change the cache wrapper has no business making",
        )
    }

    @Test
    fun loadProviderStreamsAnswersWithWhateverTheLoaderAnswered() = runTest {
        val f = fixture(providerResult = result("prov"))

        val answer = f.resolver.loadProviderStreams(MetadataLabMediaType.MOVIE, "tt1", "prov")

        assertEquals(result("prov"), answer)
        assertEquals(
            listOf("tt1", "prov"),
            listOf(f.loader.lastProviderLookupId, f.loader.lastProviderId),
            "both arguments arrive in order",
        )
    }

    @Test
    fun loadProviderStreamsAnswersNullWhenTheLoaderFindsNothing() = runTest {
        val f = fixture(providerResult = null)

        assertNull(f.resolver.loadProviderStreams(MetadataLabMediaType.MOVIE, "tt1", "prov"))
    }

    @Test
    fun aFailedLoadProviderStreamsIsLoggedAndRethrown() = runTest {
        val boom = IllegalArgumentException("bad provider id")
        val f = fixture(providerFailure = boom)

        val thrown =
            assertFailsWith<IllegalArgumentException> {
                f.resolver.loadProviderStreams(MetadataLabMediaType.MOVIE, "tt1", "prov")
            }

        assertSame(boom, thrown)
        assertEquals(listOf(boom), f.logger.errors.map { it.throwable })
    }

    @Test
    fun loadProviderStreamsDoesNotTouchTheResolveCache() = runTest {
        val f = fixture(providerResult = result("prov"))

        f.resolver.loadProviderStreams(MetadataLabMediaType.MOVIE, "tt1", "prov")

        assertNull(
            f.resolver.cachedStreams(StreamLookupTarget(MetadataLabMediaType.MOVIE, "tt1")),
            "only resolve() writes the cache, so a provider fetch never leaves an entry behind",
        )
    }

    @Test
    fun fetchAddonSubtitlesDelegatesWithTheSameArgumentsAndTheSameList() = runTest {
        val subtitles = listOf(AddonSubtitle(id = "s1", url = "u", language = "eng", display = "English"))
        val f = fixture(subtitles = subtitles)

        val answer = f.resolver.fetchAddonSubtitles(MetadataLabMediaType.SERIES, "tt1:s1:e1")

        assertSame(subtitles, answer, "no filtering, no copying, no caching of subtitles at this layer")
        assertEquals(
            listOf("SERIES", "tt1:s1:e1"),
            listOf(f.loader.lastSubtitleMediaType, f.loader.lastSubtitleLookupId),
        )
    }

    @Test
    fun theCacheIsPerResolverInstance() = runTest {
        val first = fixture()
        val second = fixture()

        first.resolver.resolve(target(), null, null)

        assertNull(second.resolver.cachedStreams(target()), "a cache is a thing you share deliberately")
    }

    // ---------------------------------------------------------------- fixtures

    private fun fixture(
        results: List<ProviderStreamsResult> = listOf(result("prov")),
        failure: Throwable? = null,
        providerResult: ProviderStreamsResult? = null,
        providerFailure: Throwable? = null,
        subtitles: List<AddonSubtitle> = emptyList(),
    ): Fixture {
        val clock = Clock()
        val loader = RecordingLoader(results, failure, providerResult, providerFailure, subtitles)
        val logger = RecordingLogger()
        return Fixture(
            clock = clock,
            loader = loader,
            logger = logger,
            resolver = CachingStreamResolver(loader, logger) { clock.nowMs },
        )
    }

    private fun target() = StreamLookupTarget(MetadataLabMediaType.MOVIE, "tt1")

    private class Fixture(
        val clock: Clock,
        val loader: RecordingLoader,
        val logger: RecordingLogger,
        val resolver: CachingStreamResolver,
    )

    /** A clock the test moves. `RESOLVE_TTL_MS` is private, so the value is restated. */
    private class Clock {
        companion object {
            const val START = 1_700_000_000_000L
            const val TTL = 5L * 60 * 1000
        }

        var nowMs: Long = START
    }

    /**
     * A loader that answers from its fields and records what it was asked. Every
     * assertion in this suite is about something production computed — a key, a
     * call count, an argument — never about a string this double invented.
     */
    private class RecordingLoader(
        var streams: List<ProviderStreamsResult>,
        private val failure: Throwable?,
        private val providerResult: ProviderStreamsResult?,
        private val providerFailure: Throwable?,
        private val subtitles: List<AddonSubtitle>,
    ) : AddonStreamsLoader {
        var loadStreamsCalls = 0
        var lastPreferredProviderId: String? = null
        var lastOnProvidersResolved: ((List<StreamProviderDescriptor>) -> Unit)? = null
        var lastOnProviderResult: ((ProviderStreamsResult) -> Unit)? = null
        var lastLookupId: String? = null
        var lastProviderId: String? = null
        var lastProviderLookupId: String? = null
        var lastSubtitleMediaType: String? = null
        var lastSubtitleLookupId: String? = null

        override suspend fun loadStreams(
            mediaType: MetadataLabMediaType,
            lookupId: String,
            preferredProviderId: String?,
            onProvidersResolved: ((List<StreamProviderDescriptor>) -> Unit)?,
            onProviderResult: ((ProviderStreamsResult) -> Unit)?,
        ): List<ProviderStreamsResult> {
            loadStreamsCalls += 1
            lastPreferredProviderId = preferredProviderId
            lastOnProvidersResolved = onProvidersResolved
            lastOnProviderResult = onProviderResult
            lastLookupId = lookupId
            failure?.let { throw it }
            return streams
        }

        override suspend fun loadProviderStreams(
            mediaType: MetadataLabMediaType,
            lookupId: String,
            providerId: String,
        ): ProviderStreamsResult? {
            lastProviderLookupId = lookupId
            lastProviderId = providerId
            providerFailure?.let { throw it }
            return providerResult
        }

        override suspend fun fetchAddonSubtitles(
            mediaType: MetadataLabMediaType,
            lookupId: String,
        ): List<AddonSubtitle> {
            lastSubtitleMediaType = mediaType.name
            lastSubtitleLookupId = lookupId
            return subtitles
        }
    }

    private class LogCall(val tag: String, val message: String, val throwable: Throwable?)

    /** Equality by identity of the throwable only: the messages carry a lookup id. */
    private data class ErrorCall(val throwable: Throwable?)

    private class RecordingLogger : AppLogger {
        val errors = mutableListOf<LogCall>()
        val warns = mutableListOf<LogCall>()
        val debugs = mutableListOf<LogCall>()
        val infos = mutableListOf<LogCall>()

        override fun debug(tag: String, message: String) {
            debugs += LogCall(tag, message, null)
        }

        override fun info(tag: String, message: String) {
            infos += LogCall(tag, message, null)
        }

        override fun warn(tag: String, message: String, throwable: Throwable?) {
            warns += LogCall(tag, message, throwable)
        }

        override fun error(tag: String, message: String, throwable: Throwable?) {
            errors += LogCall(tag, message, throwable)
        }
    }

    private companion object {
        fun result(providerId: String) =
            ProviderStreamsResult(
                providerId = providerId,
                providerName = "Provider $providerId",
                streams = emptyList(),
            )
    }
}
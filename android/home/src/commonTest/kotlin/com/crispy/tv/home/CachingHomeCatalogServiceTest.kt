package com.crispy.tv.home

import com.crispy.tv.backend.ProfileHomeSection
import com.crispy.tv.domain.home.HomeCatalogSnapshot
import com.crispy.tv.domain.watch.parseIso8601InstantToEpochMillis
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The home catalog algorithm, in the source set it can finally be tested in.
 *
 * Every case below asserts a **decision** the service makes rather than a shape
 * it produces, and each one is a decision a rewrite could plausibly get wrong:
 *
 * - **signed out** reads the cache under a null profile and never calls the
 *   server, because a signed-out app has no profile to key a fetch on;
 * - **a successful fetch** hands the snapshot *and the server's expiry* to the
 *   cache port, and the expiry crosses as the raw string;
 * - **the expiry is parsed for callers**, so `cachedHomeExpiresAtMs()` is a
 *   number rather than the string the cache holds;
 * - **two concurrent loads share one fetch** (the in-flight map), which is why
 *   this suite launches two coroutines instead of calling twice;
 * - **a failed fetch falls back to the cached snapshot** rather than emptying
 *   home, and **with nothing cached it reports the failure**;
 * - **a catalog page names the section the feed handed out**, asserted as a
 *   round trip rather than against a hand-written id, because the id the feed
 *   builds is the thing under test;
 * - **an episode becomes a show** and a **blank title is dropped**.
 *
 * The cache is a recording double rather than `NoHomeCatalogSnapshotCache`,
 * because the double is what lets the fallback case be *seeded*: it captures the
 * snapshot production wrote, so the fixture is not a hand-built guess at the
 * model's shape.
 */
class CachingHomeCatalogServiceTest {
    private val backend = RecordingBackendApi()
    private val resolver = FixedBackendContextResolver(null)
    private val cache = RecordingSnapshotCache()

    private fun service(scope: kotlinx.coroutines.CoroutineScope) = CachingHomeCatalogService(
        backendClient = backend,
        backendContextResolver = resolver,
        cache = cache,
        serviceScope = scope,
    )

    private fun heroSection(vararg cards: com.crispy.tv.backend.ClientMediaCard) = ProfileHomeSection(
        listKey = "hero",
        title = "Hero",
        subtitle = null,
        layout = "heroCarousel",
        items = cards.toList(),
        meta = emptyMap(),
    )

    private fun railSection(vararg cards: com.crispy.tv.backend.ClientMediaCard) = ProfileHomeSection(
        listKey = "popular",
        title = "Popular",
        subtitle = null,
        layout = "rail",
        items = cards.toList(),
        meta = emptyMap(),
    )

    @Test
    fun signedOutReadsTheCacheAndNeverCallsTheServer() = runTest {
        resolver.answerWithNothing()

        val result = service(this).loadPrimaryHomeFeed()

        assertEquals(emptyList(), backend.homeCalls, "a signed-out app has no profile to fetch for")
        assertEquals(listOf<String?>(null), cache.reads, "the null profile is the global cache entry")
        assertEquals(
            "Sign in and select a profile to load recommendations.",
            result.sectionsStatusMessage,
        )
    }

    @Test
    fun aSuccessfulFetchWritesTheSnapshotAndTheServersExpiry() = runTest {
        resolver.answerWith(ACCESS_TOKEN, PROFILE_ID)
        backend.withHome(heroSection(mediaCard("tt1")))

        service(this).loadPrimaryHomeFeed()

        assertEquals(listOf(ACCESS_TOKEN to PROFILE_ID), backend.homeCalls)
        val write = cache.writes.single()
        assertEquals(PROFILE_ID, write.profileId)
        assertEquals(HOME_EXPIRES_AT, write.expiresAtIso, "the expiry crosses as the raw server string")
    }

    @Test
    fun theCachedExpiryIsHandedToCallersAsMilliseconds() = runTest {
        resolver.answerWith(ACCESS_TOKEN, PROFILE_ID)
        backend.withHome(heroSection(mediaCard("tt1")))
        val service = service(this)
        service.loadPrimaryHomeFeed()

        val expiresAtMs = service.cachedHomeExpiresAtMs()

        assertNotNull(expiresAtMs, "the expiry was written, so a caller can read it back")
        assertTrue(
            expiresAtMs > requireNotNull(parseIso8601InstantToEpochMillis("2026-01-01T00:00:00Z")),
            "a parsed instant, not the raw string or zero",
        )
    }

    @Test
    fun twoConcurrentLoadsShareOneFetch() = runTest {
        resolver.answerWith(ACCESS_TOKEN, PROFILE_ID)
        backend.withHome(railSection(mediaCard("tt1")))
        val service = service(this)

        val first = async { service.loadPrimaryHomeFeed() }
        val second = async { service.loadPrimaryHomeFeed() }
        first.await()
        second.await()

        assertEquals(1, backend.homeCalls.size, "the in-flight snapshot is shared, not re-fetched")
        assertEquals(1, cache.writes.size, "and it is written once")
    }

    @Test
    fun aFailedFetchFallsBackToTheCachedSnapshot() = runTest {
        resolver.answerWith(ACCESS_TOKEN, PROFILE_ID)
        backend.withHome(railSection(mediaCard("tt1")))
        val service = service(this)
        service.loadPrimaryHomeFeed()

        backend.homeFailure = IllegalStateException("network is down")
        val afterFailure = service.loadPrimaryHomeFeed()

        assertTrue(afterFailure.sections.isNotEmpty(), "an idle app must not paint an empty home")
        assertFalse(
            afterFailure.sectionsStatusMessage.contains("network is down"),
            "the cached snapshot answers, so the failure text is not what the user sees",
        )
    }

    @Test
    fun aFailedFetchWithNothingCachedReportsTheFailure() = runTest {
        resolver.answerWith(ACCESS_TOKEN, PROFILE_ID)
        backend.homeFailure = IllegalStateException("network is down")

        val result = service(this).loadPrimaryHomeFeed()

        assertEquals("network is down", result.sectionsStatusMessage)
    }

    @Test
    fun aCatalogPageNamesTheSectionTheFeedHandedOut() = runTest {
        resolver.answerWith(ACCESS_TOKEN, PROFILE_ID)
        backend.withHome(railSection(mediaCard("tt1")))
        val service = service(this)
        val section = service.loadPrimaryHomeFeed().sections.single()

        val page = service.fetchCatalogPage(section, page = 2, pageSize = 10)

        val attempted = page.attemptedUrls.single()
        assertTrue(attempted.contains(section.catalogId), "the id the feed built is the id the page names: $attempted")
        assertTrue(attempted.contains("page=2"), "and the page asked for: $attempted")
    }

    @Test
    fun anEpisodeBecomesAShowAndABlankTitleIsDropped() = runTest {
        resolver.answerWith(ACCESS_TOKEN, PROFILE_ID)
        backend.withHome(
            railSection(
                mediaCard("tt1", mediaType = "episode"),
                mediaCard("tt2", title = "   "),
            ),
        )
        val service = service(this)
        val section = service.loadPrimaryHomeFeed().sections.single()

        val page = service.fetchCatalogPage(section, page = 1, pageSize = 10)

        assertEquals(listOf("tt1"), page.items.map { it.itemId }, "a card with no title is not an item")
        assertEquals("show", page.items.single().type, "an episode is a show in the catalog")
    }

    @Test
    fun withoutAConfiguredCacheThereIsNothingToRead() = runTest {
        resolver.answerWithNothing()

        val service = CachingHomeCatalogService(
            backendClient = backend,
            backendContextResolver = resolver,
            cache = NoHomeCatalogSnapshotCache,
            serviceScope = this,
        )

        assertNull(service.loadCachedPrimaryHomeFeed())
        assertNull(service.cachedHomeExpiresAtMs())
    }
}

/**
 * The cache port, recorded. `stored` is keyed by the profile id the service
 * passed, so a read of the wrong key answers `null` and a write to the wrong key
 * is visible as a missing read — which is the failure this port could have.
 */
private class RecordingSnapshotCache : HomeCatalogSnapshotCache {
    val reads = mutableListOf<String?>()
    val writes = mutableListOf<Write>()
    val stored = mutableMapOf<String?, CachedHomeCatalogSnapshot>()

    data class Write(val profileId: String?, val snapshot: HomeCatalogSnapshot, val expiresAtIso: String?)

    override suspend fun read(profileId: String?): CachedHomeCatalogSnapshot? {
        reads += profileId
        return stored[profileId]
    }

    override suspend fun write(profileId: String?, snapshot: HomeCatalogSnapshot, expiresAtIso: String?) {
        writes += Write(profileId, snapshot, expiresAtIso)
        stored[profileId] = CachedHomeCatalogSnapshot(snapshot = snapshot, expiresAtIso = expiresAtIso)
    }
}
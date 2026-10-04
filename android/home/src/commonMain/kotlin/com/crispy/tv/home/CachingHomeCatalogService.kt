package com.crispy.tv.home

import com.crispy.tv.backend.BackendContext
import com.crispy.tv.backend.BackendContextResolver
import com.crispy.tv.backend.BackendApi
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.catalog.CatalogPageResult
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.domain.home.HomeCatalogItem
import com.crispy.tv.domain.home.HomeCatalogList
import com.crispy.tv.domain.home.HomeCatalogPresentation
import com.crispy.tv.domain.home.HomeCatalogSnapshot
import com.crispy.tv.domain.home.HomeCatalogSource
import com.crispy.tv.domain.home.HomeRandomCandidate
import com.crispy.tv.domain.home.buildCatalogPage
import com.crispy.tv.domain.home.planPersonalHomeFeed
import com.crispy.tv.domain.home.toRandomCandidates
import com.crispy.tv.addons.util.formatRating
import com.crispy.tv.images.ResponsiveImageSet
import com.crispy.tv.images.responsiveImageSetFromDomainMap
import com.crispy.tv.images.toDomainMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.crispy.tv.domain.watch.parseIso8601InstantToEpochMillis
import com.crispy.tv.backend.ClientMediaCard
import com.crispy.tv.backend.ProfileHomeResponse
import com.crispy.tv.backend.ProfileHomeSection
private const val PREVIEW_ITEM_LIMIT = 12

/**
 * The cache key a profile's snapshot is stored under, and the one a signed-out
 * reader falls back to.
 *
 * `internal` rather than private because both halves of the cache need them: the
 * service uses the per-profile key to deduplicate in-flight fetches, and
 * `DiskHomeCatalogSnapshotCache` needs the same keys to read and write the same
 * files. One definition, because a key only one side knows is a cache that
 * silently stops hitting.
 */
internal const val GLOBAL_CACHE_KEY = "home_snapshot:last"

/**
 * The variant key a list with a blank one is stored under.
 *
 * `internal` for the same reason as [GLOBAL_CACHE_KEY]: the wire mapping in
 * `DiskHomeCatalogSnapshotCache` defaults to it while parsing, and the
 * normalisation below defaults to it while reading the wire.
 */
internal const val DEFAULT_VARIANT_KEY = "default"

internal fun homeCacheKey(profileId: String): String = "home_snapshot:${profileId.trim()}"


/**
 * [HomeCatalogService]: fetch the feed, deduplicate concurrent fetches, and fall
 * back to the last snapshot when the network fails.
 *
 * Portable now that the only platform step — writing the snapshot to disk —
 * sits behind [HomeCatalogSnapshotCache]. The mapping left this file for
 * [DiskHomeCatalogSnapshotCache], and nothing else moved: the plan, the fallback
 * rules and the dedup are all plain Kotlin.
 *
 * [serviceScope] is required rather than defaulted. The obvious default is
 * `CoroutineScope(SupervisorJob() + Dispatchers.IO)`, and a `commonMain` class
 * cannot even name `Dispatchers.IO` — it is `internal` on Kotlin/Native, so the
 * compiler answers "cannot access", not "unresolved". Defaulting it would also
 * hide the lifetime decision: this scope is never cancelled, so whoever creates
 * it owns a job that outlives every call.
 */
class CachingHomeCatalogService constructor(
    private val backendClient: BackendApi,
    private val backendContextResolver: BackendContextResolver,
    private val cache: HomeCatalogSnapshotCache,
    private val serviceScope: CoroutineScope,
) : HomeCatalogService {
    private val inFlightMutex = Mutex()
    private val inFlightSnapshots = mutableMapOf<String, Deferred<HomeCatalogSnapshot>>()
    override suspend fun loadPrimaryHomeFeed(
        sectionLimit: Int,
    ): HomePrimaryFeedLoadResult {
        val snapshot = loadSnapshot()
        return snapshot.toPrimaryHomeFeedLoadResult(sectionLimit = sectionLimit)
    }

    override suspend fun loadCachedPrimaryHomeFeed(
        sectionLimit: Int,
    ): HomePrimaryFeedLoadResult? {
        val backendContext = getBackendContext()
        // Stale-while-revalidate: reuse the last snapshot regardless of age so a
        // restored/idle app always repaints, then revalidate via loadPrimaryHomeFeed.
        val snapshot = cache.read(profileId = backendContext?.profileId)?.snapshot
            ?: return null
        return snapshot.toPrimaryHomeFeedLoadResult(sectionLimit = sectionLimit)
    }

    override suspend fun fetchCatalogPage(
        section: CatalogSectionRef,
        page: Int,
        pageSize: Int,
    ): CatalogPageResult {
        val snapshot = loadSnapshot()
        val result = buildCatalogPage(snapshot, sectionCatalogId = section.catalogId, page = page, pageSize = pageSize)
        return CatalogPageResult(
            items = result.items.mapNotNull { item -> item.toCatalogItem() },
            statusMessage = result.statusMessage,
            attemptedUrls = listOf(homeAttemptedUrl(snapshot.profileId, section.catalogId, page)),
        )
    }

    private suspend fun loadSnapshot(): HomeCatalogSnapshot {
        val backendContext = getBackendContext()
        if (backendContext == null) {
            return cache.read(profileId = null)?.snapshot
                ?: emptySnapshot("Sign in and select a profile to load recommendations.")
        }

        val requestKey = homeCacheKey(backendContext.profileId)
        val deferred = inFlightMutex.withLock {
            inFlightSnapshots[requestKey] ?: serviceScope.async {
                loadSnapshotUncached(backendContext)
            }.also { created ->
                inFlightSnapshots[requestKey] = created
            }
        }

        return try {
            deferred.await()
        } finally {
            inFlightMutex.withLock {
                if (inFlightSnapshots[requestKey] === deferred) {
                    inFlightSnapshots.remove(requestKey)
                }
            }
        }
    }

    private suspend fun loadSnapshotUncached(backendContext: BackendContext): HomeCatalogSnapshot {
        return try {
            val response = backendClient.getHome(
                accessToken = backendContext.accessToken,
                profileId = backendContext.profileId,
            )
            val snapshot = response?.toSnapshot() ?: emptySnapshot("No recommendations available right now.")
            cache.write(backendContext.profileId, snapshot, expiresAtIso = response?.expiresAt)
            snapshot
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            // On a failed live fetch, fall back to the last cached snapshot at any age
            // rather than discarding it (the previous 15-minute cap erased home on idle).
            cache.read(profileId = backendContext.profileId)?.snapshot
                ?: emptySnapshot(error.message ?: "Failed to load recommendations.")
        }
    }

    private suspend fun getBackendContext(): BackendContext? {
        return backendContextResolver.resolve()
    }

    /**
     * The expiry the cached snapshot was written with.
     *
     * This used to re-read and re-parse the cache file purely for `expires_at`,
     * looping over the same keys `readCachedSnapshot` walked. It is now one read:
     * [HomeCatalogSnapshotCache.read] hands back the expiry string it stored,
     * because the writer always wrote the same payload under both keys, so the two
     * answers could not disagree. Parsing stays here because
     * `parseIso8601InstantToEpochMillis` is `core-domain` code — the platform side
     * stores what it was given and does not interpret it.
     */
    override suspend fun cachedHomeExpiresAtMs(): Long? {
        val backendContext = getBackendContext()
        val expiresAt = cache.read(profileId = backendContext?.profileId)
            ?.expiresAtIso
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return parseIso8601InstantToEpochMillis(expiresAt)
    }

    /**
     * One read of every home item, for the random-pick wheel.
     *
     * `loadSnapshot` is called directly rather than per section: the wheel wants
     * the whole snapshot, and a `fetchCatalogPage` loop would re-enter this class
     * once per home row. `toRandomCandidates` is what collapses the overlap
     * between hero, pill and rail lists, which are the same items by design.
     */
    override suspend fun loadRandomCandidates(): List<HomeRandomCandidate> {
        val snapshot = loadSnapshot()
        return snapshot.lists.flatMap { it.items }.toRandomCandidates()
    }

    private fun emptySnapshot(statusMessage: String): HomeCatalogSnapshot {
        return HomeCatalogSnapshot(
            profileId = null,
            lists = emptyList(),
            statusMessage = statusMessage,
        )
    }

    private fun ProfileHomeResponse.toSnapshot(): HomeCatalogSnapshot {
        return HomeCatalogSnapshot(
            profileId = profileId.takeIf { it.isNotBlank() },
            lists = buildList {
                sections.forEach { section ->
                    section.toCatalogList()?.let(::add)
                }
            },
            statusMessage = if (sections.isEmpty()) "No recommendations available right now." else "",
        )
    }

    private fun ProfileHomeSection.toCatalogList(): HomeCatalogList? {
        val catalogItems = items.mapNotNull { item -> item.toCatalogItem() }
        if (catalogItems.isEmpty()) return null
        val normalizedListKey = listKey.normalizedKind()
        return HomeCatalogList(
            kind = normalizedListKey,
            variantKey = listKey.normalizedVariantKey(),
            source = HomeCatalogSource.PERSONAL,
            presentation = layout.toPresentation(),
            layout = layout.normalizedBackendLayout(),
            name = title,
            heading = title,
            title = title,
            subtitle = subtitle?.trim().orEmpty(),
            items = catalogItems,
            mediaTypes = catalogItems.map { it.type }.toSet(),
        )
    }

    private fun ClientMediaCard.toCatalogItem(): HomeCatalogItem? {
        val normalizedItemId = itemId.trim().ifBlank { return null }
        val normalizedTitle = title.trim().ifBlank { return null }
        val artwork = images.artwork
        val logo = images.logo
        return HomeCatalogItem(
            itemId = normalizedItemId,
            title = normalizedTitle,
            artworkUrl = artwork.medium,
            logoUrl = logo.medium,
            artwork = artwork.toDomainMap(),
            logo = logo.toDomainMap(),
            addonId = "backend",
            type = mediaType.toCatalogType(),
            rating = formatRating(rating),
            year = year?.toString(),
            genre = genres.firstOrNull(),
            description = overview,
            tagline = tagline,
        )
    }

    private fun HomeCatalogItem.toCatalogItem(): CatalogItem? {
        val normalizedItemId = itemId.trim().ifBlank { return null }
        return CatalogItem(
            id = normalizedItemId,
            itemId = normalizedItemId,
            title = title,
            artworkUrl = artworkUrl,
            logoUrl = logoUrl,
            artwork = responsiveImageSetFromDomainMap(artwork),
            logo = responsiveImageSetFromDomainMap(logo),
            addonId = addonId,
            type = type,
            rating = rating,
            year = year,
            genre = genre,
            description = description,
        )
    }

    private fun String.toCatalogType(): String {
        val normalizedMediaType = trim().lowercase()
        return when (normalizedMediaType) {
            "anime" -> "anime"
            "episode", "show", "tv", "series" -> "show"
            else -> "movie"
        }
    }

    private fun String.normalizedKind(): String {
        return trim().ifBlank { "home" }
    }

    private fun String.normalizedVariantKey(): String {
        return trim().ifBlank { DEFAULT_VARIANT_KEY }
    }

    private fun String.toPresentation(): HomeCatalogPresentation {
        return when (trim().lowercase()) {
            "herocarousel", "hero", "landscape" -> HomeCatalogPresentation.HERO
            "categorytabs" -> HomeCatalogPresentation.PILL
            "collectionrail", "collection" -> HomeCatalogPresentation.COLLECTION_SHELF
            else -> HomeCatalogPresentation.RAIL
        }
    }

    private fun String.normalizedBackendLayout(): String {
        return when (trim().lowercase()) {
            "herocarousel", "hero" -> "hero"
            "landscape" -> "landscape"
            "categorytabs" -> "categoryTabs"
            "collectionrail", "collection" -> "collection"
            else -> "regular"
        }
    }

    private fun homeAttemptedUrl(profileId: String?, catalogId: String? = null, page: Int? = null): String {
        val base = "backend:/v1/profiles/${profileId.orEmpty()}/home"
        val suffix = buildList {
            catalogId?.trim()?.takeIf { it.isNotBlank() }?.let { add("catalogId=$it") }
            page?.let { add("page=$it") }
        }.joinToString("&")
        return if (suffix.isBlank()) base else "$base?$suffix"
    }

    private fun HomeCatalogSnapshot.toPrimaryHomeFeedLoadResult(
        sectionLimit: Int,
    ): HomePrimaryFeedLoadResult {
        val feedPlan = planPersonalHomeFeed(this, sectionLimit = sectionLimit)
        return HomePrimaryFeedLoadResult(
            heroResult =
                HomeHeroLoadResult(
                    items =
                        feedPlan.heroResult.items.mapNotNull { hero ->
                            HomeHeroItem(
                                id = hero.itemId,
                                title = hero.title,
                                description = hero.description,
                                tagline = hero.tagline,
                                rating = hero.rating,
                                year = hero.year,
                                genres = hero.genres,
                                artworkUrl = hero.artworkUrl,
                                artwork = responsiveImageSetFromDomainMap(hero.artwork),
                                addonId = hero.addonId,
                                type = hero.type,
                            )
                        },
                    statusMessage = feedPlan.heroResult.statusMessage,
                ),
            sections =
                feedPlan.sections.map { section ->
                    val previewItems =
                        this.lists
                            .firstOrNull { it.catalogId == section.catalogId }
                            ?.items
                            ?.take(PREVIEW_ITEM_LIMIT)
                            .orEmpty()
                            .mapNotNull { item -> item.toCatalogItem() }
                    CatalogSectionRef(
                        catalogId = section.catalogId,
                        source = section.source,
                        presentation = section.presentation,
                        layout = section.layout.orEmpty(),
                        variantKey = section.variantKey,
                        kind = section.kind,
                        name = section.name,
                        heading = section.heading,
                        title = section.title,
                        subtitle = section.subtitle,
                        previewItems = previewItems,
                    )
                },
            sectionsStatusMessage = feedPlan.sectionsStatusMessage,
        )
    }
}

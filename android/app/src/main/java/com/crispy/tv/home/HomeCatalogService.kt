package com.crispy.tv.home

import android.content.Context
import android.util.Log
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.catalog.CatalogPageResult
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.catalog.DiscoverCatalogRef
import com.crispy.tv.domain.catalog.CatalogFilter
import com.crispy.tv.domain.catalog.CatalogRequestInput
import com.crispy.tv.domain.catalog.buildCatalogRequestUrls
import com.crispy.tv.metadata.AddonManifestSeed
import com.crispy.tv.metadata.MetadataAddonRegistry
import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.ContinueWatchingEntry as ProviderContinueWatchingEntry
import com.crispy.tv.player.WatchHistoryEntry
import com.crispy.tv.player.WatchProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

data class HomeHeroItem(
    val id: String,
    val title: String,
    val description: String,
    val rating: String?,
    val year: String? = null,
    val genres: List<String> = emptyList(),
    val backdropUrl: String,
    val addonId: String,
    val type: String
)

data class HomeHeroLoadResult(
    val items: List<HomeHeroItem> = emptyList(),
    val statusMessage: String = "Home is ready."
)

data class ContinueWatchingItem(
    val id: String,
    val contentId: String,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val watchedAtEpochMs: Long,
    val progressPercent: Double,
    val provider: WatchProvider,
    val providerPlaybackId: String?,
    val isUpNextPlaceholder: Boolean = false,
    val backdropUrl: String?,
    val logoUrl: String?,
    val addonId: String?,
    val type: String
)

data class ContinueWatchingLoadResult(
    val items: List<ContinueWatchingItem> = emptyList(),
    val statusMessage: String = ""
)

data class MediaDetailsLoadResult(
    val details: MediaDetails? = null,
    val statusMessage: String = "",
    val attemptedUrls: List<String> = emptyList()
)

data class MediaDetails(
    val id: String,
    val imdbId: String?,
    val mediaType: String,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val logoUrl: String?,
    val description: String?,
    val genres: List<String> = emptyList(),
    val year: String?,
    val runtime: String?,
    val certification: String?,
    val rating: String?,
    val cast: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val creators: List<String> = emptyList(),
    val videos: List<MediaVideo> = emptyList(),
    val addonId: String?
)

data class MediaVideo(
    val id: String,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val released: String?,
    val overview: String?,
    val thumbnailUrl: String?
)

class HomeCatalogService(
    context: Context,
    addonManifestUrlsCsv: String,
    private val httpClient: CrispyHttpClient,
) {
    private val addonRegistry = MetadataAddonRegistry(context.applicationContext, addonManifestUrlsCsv)
    private val continueWatchingMetaCache = mutableMapOf<String, CachedContinueWatchingMeta>()

    @Volatile
    private var resolvedAddonsCache: List<ResolvedAddon>? = null
    @Volatile
    private var resolvedAddonsCacheTimestamp: Long = 0
    private val resolvedAddonsCacheLock = Any()

    private val manifestFetchSemaphore = Semaphore(8)
    private val metaResolveSemaphore = Semaphore(6)

    fun invalidateAddonsCache() {
        synchronized(resolvedAddonsCacheLock) {
            resolvedAddonsCache = null
            resolvedAddonsCacheTimestamp = 0
        }
    }

    suspend fun loadHeroItems(limit: Int = 10): HomeHeroLoadResult {
        val resolvedAddons = resolveAddons()
        if (resolvedAddons.isEmpty()) {
            return HomeHeroLoadResult(statusMessage = "No installed addons available.")
        }

        val candidates = catalogCandidates(resolvedAddons)
        if (candidates.isEmpty()) {
            return HomeHeroLoadResult(statusMessage = "No movie or series catalogs found in installed addons.")
        }

        val deduped = linkedMapOf<String, HomeHeroItem>()
        val targetCount = limit.coerceAtLeast(1)

        for (candidate in candidates) {
            val urls =
                buildCatalogRequestUrls(
                    CatalogRequestInput(
                        baseUrl = candidate.seed.baseUrl,
                        mediaType = candidate.catalogType,
                        catalogId = candidate.catalogId,
                        page = 1,
                        pageSize = targetCount * 2,
                        filters = emptyList(),
                        encodedAddonQuery = candidate.seed.encodedQuery
                    )
                )

            for (url in urls) {
                val response = httpGetJson(url) ?: continue
                parseHeroItems(
                    metas = response.optJSONArray("metas"),
                    addonId = candidate.addonId,
                    mediaType = candidate.catalogType
                ).forEach { item ->
                    deduped.putIfAbsent(item.id, item)
                }
                if (deduped.size >= targetCount) {
                    return HomeHeroLoadResult(
                        items = deduped.values.take(targetCount),
                        statusMessage = "Loaded featured items from ${candidate.name}."
                    )
                }
            }
        }

        if (deduped.isNotEmpty()) {
            return HomeHeroLoadResult(
                items = deduped.values.take(targetCount),
                statusMessage = "Loaded featured items from installed addons."
            )
        }

        return HomeHeroLoadResult(statusMessage = "No featured catalog items available from addons.")
    }

    suspend fun loadContinueWatchingItems(
        entries: List<WatchHistoryEntry>,
        limit: Int = 20
    ): ContinueWatchingLoadResult {
        val targetCount = limit.coerceAtLeast(1)
        val dedupedEntries = entries
            .sortedByDescending { it.watchedAtEpochMs }
            .distinctBy { continueWatchingKey(it) }
            .take(targetCount)

        if (dedupedEntries.isEmpty()) {
            return ContinueWatchingLoadResult(statusMessage = "No continue watching items yet.")
        }

        val resolvedAddons = resolveAddons()
        val items = coroutineScope {
            dedupedEntries.map { entry ->
                async(Dispatchers.IO) {
                    metaResolveSemaphore.acquire()
                    try {
                        val mediaType = entry.asCatalogMediaType()
                        val resolvedMeta = resolveContinueWatchingMeta(entry, mediaType, resolvedAddons)
                        ContinueWatchingItem(
                            id = continueWatchingKey(entry),
                            contentId = entry.contentId,
                            title = resolvedMeta?.title ?: fallbackContinueWatchingTitle(entry),
                            season = entry.season,
                            episode = entry.episode,
                            watchedAtEpochMs = entry.watchedAtEpochMs,
                            progressPercent = 100.0,
                            provider = WatchProvider.LOCAL,
                            providerPlaybackId = null,
                            isUpNextPlaceholder = false,
                            backdropUrl = resolvedMeta?.backdropUrl,
                            logoUrl = resolvedMeta?.logoUrl,
                            addonId = resolvedMeta?.addonId,
                            type = mediaType
                        )
                    } finally {
                        metaResolveSemaphore.release()
                    }
                }
            }.awaitAll()
        }

        return ContinueWatchingLoadResult(
            items = items,
            statusMessage = "Loaded continue watching from watch history."
        )
    }

    suspend fun loadContinueWatchingItemsFromProvider(
        entries: List<ProviderContinueWatchingEntry>,
        limit: Int = 20
    ): ContinueWatchingLoadResult {
        val targetCount = limit.coerceAtLeast(1)
        val dedupedEntries = entries
            .sortedByDescending { it.lastUpdatedEpochMs }
            .distinctBy { "${it.contentType.name}:${it.contentId}:${it.season ?: -1}:${it.episode ?: -1}" }
            .take(targetCount)

        if (dedupedEntries.isEmpty()) {
            return ContinueWatchingLoadResult(statusMessage = "No continue watching items yet.")
        }

        val resolvedAddons = resolveAddons()
        val items = coroutineScope {
            dedupedEntries.map { entry ->
                async(Dispatchers.IO) {
                    metaResolveSemaphore.acquire()
                    try {
                        val fakeWatchEntry = WatchHistoryEntry(
                            contentId = entry.contentId,
                            contentType = entry.contentType,
                            title = entry.title,
                            season = entry.season,
                            episode = entry.episode,
                            watchedAtEpochMs = entry.lastUpdatedEpochMs
                        )
                        val mediaType = fakeWatchEntry.asCatalogMediaType()
                        val resolvedMeta = resolveContinueWatchingMeta(fakeWatchEntry, mediaType, resolvedAddons)
                        ContinueWatchingItem(
                            id = "${entry.provider.name.lowercase(Locale.US)}:${entry.contentType.name.lowercase(Locale.US)}:${entry.contentId}:${entry.season ?: -1}:${entry.episode ?: -1}",
                            contentId = entry.contentId,
                            title = resolvedMeta?.title ?: entry.title,
                            season = entry.season,
                            episode = entry.episode,
                            watchedAtEpochMs = entry.lastUpdatedEpochMs,
                            progressPercent = entry.progressPercent,
                            provider = entry.provider,
                            providerPlaybackId = entry.providerPlaybackId,
                            isUpNextPlaceholder = entry.isUpNextPlaceholder,
                            backdropUrl = resolvedMeta?.backdropUrl,
                            logoUrl = resolvedMeta?.logoUrl,
                            addonId = resolvedMeta?.addonId,
                            type = mediaType
                        )
                    } finally {
                        metaResolveSemaphore.release()
                    }
                }
            }.awaitAll()
        }

        return ContinueWatchingLoadResult(
            items = items,
            statusMessage = "Loaded continue watching from provider playback."
        )
    }

    suspend fun listHomeCatalogSections(limit: Int = 15): Pair<List<CatalogSectionRef>, String> {
        val targetCount = limit.coerceAtLeast(1)
        val resolvedAddons = resolveAddons()
        if (resolvedAddons.isEmpty()) {
            return emptyList<CatalogSectionRef>() to "No installed addons available."
        }

        val sections = mutableListOf<CatalogSectionRef>()
        val seenKeys = mutableSetOf<String>()

        resolvedAddons.forEach { addon ->
            val manifest = addon.manifest ?: return@forEach
            val catalogs = manifest.optJSONArray("catalogs") ?: return@forEach
            for (index in 0 until catalogs.length()) {
                val catalog = catalogs.optJSONObject(index) ?: continue
                val type = nonBlank(catalog.optString("type"))?.lowercase(Locale.US) ?: continue
                if (type != "movie" && type != "series") {
                    continue
                }
                val id = nonBlank(catalog.optString("id")) ?: continue
                val key = "$type:$id"
                if (!seenKeys.add(key)) {
                    continue
                }
                val name = nonBlank(catalog.optString("name")) ?: id
                sections +=
                    CatalogSectionRef(
                        title = name,
                        catalogId = id,
                        mediaType = type,
                        addonId = addon.addonId,
                        baseUrl = addon.seed.baseUrl,
                        encodedAddonQuery = addon.seed.encodedQuery
                    )
                if (sections.size >= targetCount) {
                    return sections to "Loaded ${sections.size} catalogs."
                }
            }
        }

        if (sections.isEmpty()) {
            return emptyList<CatalogSectionRef>() to "No movie or series catalogs found in installed addons."
        }

        return sections to "Loaded ${sections.size} catalogs."
    }

    suspend fun listDiscoverCatalogs(
        mediaType: String? = null,
        limit: Int = 50
    ): Pair<List<DiscoverCatalogRef>, String> {
        val normalizedType =
            mediaType
                ?.trim()
                ?.lowercase(Locale.US)
                ?.takeIf { it.isNotBlank() }
        if (normalizedType != null && normalizedType != "movie" && normalizedType != "series") {
            return emptyList<DiscoverCatalogRef>() to "Unsupported media type: $mediaType"
        }

        val targetCount = limit.coerceAtLeast(1)
        val resolvedAddons = resolveAddons()
        if (resolvedAddons.isEmpty()) {
            return emptyList<DiscoverCatalogRef>() to "No installed addons available."
        }

        val catalogs = mutableListOf<DiscoverCatalogRef>()
        val seenKeys = mutableSetOf<String>()

        resolvedAddons.forEach { addon ->
            val manifest = addon.manifest ?: return@forEach
            val addonName = nonBlank(manifest.optString("name")) ?: addon.addonId
            val manifestCatalogs = manifest.optJSONArray("catalogs") ?: return@forEach

            for (index in 0 until manifestCatalogs.length()) {
                val catalog = manifestCatalogs.optJSONObject(index) ?: continue
                val type = nonBlank(catalog.optString("type"))?.lowercase(Locale.US) ?: continue
                if (type != "movie" && type != "series") {
                    continue
                }
                if (normalizedType != null && type != normalizedType) {
                    continue
                }
                val id = nonBlank(catalog.optString("id")) ?: continue
                val key = "${addon.addonId.lowercase(Locale.US)}:$type:${id.lowercase(Locale.US)}"
                if (!seenKeys.add(key)) {
                    continue
                }
                val name = nonBlank(catalog.optString("name")) ?: id
                val genres = parseGenreOptions(catalog)

                catalogs +=
                    DiscoverCatalogRef(
                        section =
                            CatalogSectionRef(
                                title = name,
                                catalogId = id,
                                mediaType = type,
                                addonId = addon.addonId,
                                baseUrl = addon.seed.baseUrl,
                                encodedAddonQuery = addon.seed.encodedQuery
                            ),
                        addonName = addonName,
                        genres = genres
                    )

                if (catalogs.size >= targetCount) {
                    return catalogs to "Loaded ${catalogs.size} discover catalogs."
                }
            }
        }

        if (catalogs.isEmpty()) {
            val suffix = if (normalizedType == null) "" else " for $normalizedType"
            return emptyList<DiscoverCatalogRef>() to "No discover catalogs found$suffix in installed addons."
        }

        return catalogs to "Loaded ${catalogs.size} discover catalogs."
    }

    suspend fun loadMediaDetails(
        rawId: String,
        preferredAddonId: String? = null,
        preferredMediaType: String? = null
    ): MediaDetailsLoadResult {
        val requestedId = rawId.trim()
        if (requestedId.isBlank()) {
            return MediaDetailsLoadResult(statusMessage = "Missing content id.")
        }

        val seeds = addonRegistry.orderedSeeds()
        if (seeds.isEmpty()) {
            return MediaDetailsLoadResult(statusMessage = "No installed addons available.")
        }

        val mediaTypesToTry = buildMediaTypesToTry(preferredMediaType)
        val lookupIds = lookupIdsForContinueWatching(requestedId)
        if (lookupIds.isEmpty()) {
            return MediaDetailsLoadResult(statusMessage = "Missing content id.")
        }

        val orderedSeeds =
            if (preferredAddonId.isNullOrBlank()) {
                seeds
            } else {
                seeds.sortedBy { seed ->
                    if (seed.addonIdHint.equals(preferredAddonId, ignoreCase = true)) 0 else 1
                }
            }

        val attemptedUrls = mutableListOf<String>()
        for (mediaType in mediaTypesToTry) {
            for (seed in orderedSeeds) {
                for (lookupId in lookupIds) {
                    val url = buildMetaUrl(seed = seed, mediaType = mediaType, lookupId = lookupId)
                    attemptedUrls += url
                    val response = httpGetJson(url) ?: continue
                    val meta = response.optJSONObject("meta") ?: continue
                    val parsed =
                        parseMediaDetails(
                            meta = meta,
                            fallbackId = lookupId,
                            fallbackMediaType = mediaType,
                            addonId = seed.addonIdHint
                        ) ?: continue
                    return MediaDetailsLoadResult(
                        details = parsed,
                        statusMessage = "Loaded details.",
                        attemptedUrls = attemptedUrls
                    )
                }
            }
        }

        return MediaDetailsLoadResult(
            statusMessage = "No metadata found for $requestedId.",
            attemptedUrls = attemptedUrls
        )
    }

    suspend fun fetchCatalogPage(
        section: CatalogSectionRef,
        page: Int,
        pageSize: Int,
        filters: List<CatalogFilter> = emptyList()
    ): CatalogPageResult {
        val targetPage = page.coerceAtLeast(1)
        val targetSize = pageSize.coerceAtLeast(1)
        val attemptedUrls = mutableListOf<String>()

        val urls =
            runCatching {
                buildCatalogRequestUrls(
                    CatalogRequestInput(
                        baseUrl = section.baseUrl,
                        mediaType = section.mediaType,
                        catalogId = section.catalogId,
                        page = targetPage,
                        pageSize = targetSize,
                        filters = filters,
                        encodedAddonQuery = section.encodedAddonQuery
                    )
                )
            }.getOrElse { error ->
                return CatalogPageResult(
                    items = emptyList(),
                    statusMessage = error.message ?: "Failed to build catalog request.",
                    attemptedUrls = emptyList()
                )
            }

        for ((index, url) in urls.withIndex()) {
            attemptedUrls += url
            val response = httpGetJson(url) ?: continue
            val items =
                parseCatalogItems(
                    metas = response.optJSONArray("metas"),
                    addonId = section.addonId,
                    mediaType = section.mediaType
                )

            if (items.isEmpty() && index < urls.lastIndex) {
                continue
            }

            return CatalogPageResult(
                items = items,
                statusMessage = if (items.isEmpty()) "No catalog items available." else "Loaded ${items.size} items.",
                attemptedUrls = attemptedUrls
            )
        }

        return CatalogPageResult(
            items = emptyList(),
            statusMessage = "No catalog items available.",
            attemptedUrls = attemptedUrls
        )
    }

    private fun parseGenreOptions(catalog: JSONObject): List<String> {
        val extras = catalog.optJSONArray("extra") ?: return emptyList()
        val genres = LinkedHashSet<String>()

        for (index in 0 until extras.length()) {
            val extra = extras.optJSONObject(index) ?: continue
            val extraName = nonBlank(extra.optString("name"))?.lowercase(Locale.US) ?: continue
            if (extraName != "genre") {
                continue
            }

            val options = extra.optJSONArray("options") ?: continue
            for (optionIndex in 0 until options.length()) {
                val option = nonBlank(options.optString(optionIndex)) ?: continue
                genres += option
            }
        }

        return genres.toList()
    }

    private suspend fun resolveAddons(): List<ResolvedAddon> {
        val now = System.currentTimeMillis()
        synchronized(resolvedAddonsCacheLock) {
            val cached = resolvedAddonsCache
            if (cached != null && (now - resolvedAddonsCacheTimestamp) < RESOLVED_ADDONS_CACHE_TTL_MS) {
                return cached
            }
        }

        val seeds = addonRegistry.orderedSeeds()
        if (seeds.isEmpty()) {
            return emptyList()
        }

        val resolved = coroutineScope {
            seeds.mapIndexed { index, seed ->
                async(Dispatchers.IO) {
                    manifestFetchSemaphore.acquire()
                    try {
                        val networkManifest = httpGetJson(seed.manifestUrl)
                        if (networkManifest != null) {
                            addonRegistry.cacheManifest(seed, networkManifest)
                        }
                        val manifest = networkManifest ?: parseCachedManifest(seed.cachedManifestJson) ?: fallbackManifestFor(seed)
                        val addonId = nonBlank(manifest?.optString("id")) ?: seed.addonIdHint
                        ResolvedAddon(
                            orderIndex = index,
                            seed = seed,
                            addonId = addonId,
                            manifest = manifest
                        )
                    } finally {
                        manifestFetchSemaphore.release()
                    }
                }
            }.awaitAll()
        }

        synchronized(resolvedAddonsCacheLock) {
            resolvedAddonsCache = resolved
            resolvedAddonsCacheTimestamp = System.currentTimeMillis()
        }

        return resolved
    }

    private fun catalogCandidates(resolvedAddons: List<ResolvedAddon>): List<CatalogCandidate> {
        val candidates = mutableListOf<CatalogCandidate>()

        resolvedAddons.forEach { addon ->
            val manifest = addon.manifest ?: return@forEach
            val catalogs = manifest.optJSONArray("catalogs") ?: return@forEach
            for (index in 0 until catalogs.length()) {
                val catalog = catalogs.optJSONObject(index) ?: continue
                val type = nonBlank(catalog.optString("type"))?.lowercase() ?: continue
                if (type != "movie" && type != "series") {
                    continue
                }
                val id = nonBlank(catalog.optString("id")) ?: continue
                val name = nonBlank(catalog.optString("name")) ?: id
                candidates +=
                    CatalogCandidate(
                        addonOrderIndex = addon.orderIndex,
                        addonId = addon.addonId,
                        seed = addon.seed,
                        catalogId = id,
                        catalogType = type,
                        name = name,
                        priority = catalogPriority(id, name)
                    )
            }
        }

        return candidates.sortedWith(
            compareBy<CatalogCandidate> { it.addonOrderIndex }
                .thenBy { it.priority }
                .thenBy { it.name.lowercase(Locale.US) }
        )
    }

    private fun catalogPriority(catalogId: String, catalogName: String): Int {
        val key = "$catalogId $catalogName".lowercase(Locale.US)
        return when {
            key.contains("featured") -> 0
            key.contains("top") -> 1
            key.contains("trending") -> 2
            key.contains("popular") -> 3
            key.contains("new") -> 4
            else -> 5
        }
    }

    private fun parseHeroItems(metas: JSONArray?, addonId: String, mediaType: String): List<HomeHeroItem> {
        if (metas == null) {
            return emptyList()
        }

        val items = mutableListOf<HomeHeroItem>()
        for (index in 0 until metas.length()) {
            val meta = metas.optJSONObject(index) ?: continue
            val id = nonBlank(meta.optString("id")) ?: continue
            val title =
                nonBlank(meta.optString("name"))
                    ?: nonBlank(meta.optString("title"))
                    ?: continue
            val backdrop =
                nonBlank(meta.optString("background"))
                    ?: nonBlank(meta.optString("poster"))
                    ?: continue
            val description = nonBlank(meta.optString("description")) ?: "No description provided."
            val rating = parseRating(meta)
            val type = nonBlank(meta.optString("type")) ?: mediaType
            val year = extractYear(meta)
            val genres = readStringList(meta, "genres")

            items +=
                HomeHeroItem(
                    id = id,
                    title = title,
                    description = description,
                    rating = rating,
                    year = year,
                    genres = genres,
                    backdropUrl = backdrop,
                    addonId = addonId,
                    type = type
                )
        }
        return items
    }

    private fun parseCatalogItems(metas: JSONArray?, addonId: String, mediaType: String): List<CatalogItem> {
        if (metas == null) {
            return emptyList()
        }

        val items = mutableListOf<CatalogItem>()
        for (index in 0 until metas.length()) {
            val meta = metas.optJSONObject(index) ?: continue
            val id = nonBlank(meta.optString("id")) ?: continue
            val title =
                nonBlank(meta.optString("name"))
                    ?: nonBlank(meta.optString("title"))
                    ?: continue
            val poster = nonBlank(meta.optString("poster"))
            val backdrop = nonBlank(meta.optString("background"))
            val type = nonBlank(meta.optString("type")) ?: mediaType
            val rating = parseRating(meta)
            items +=
                CatalogItem(
                    id = id,
                    title = title,
                    posterUrl = poster,
                    backdropUrl = backdrop,
                    addonId = addonId,
                    type = type,
                    rating = rating
                )
        }
        return items
    }

    private fun parseRating(meta: JSONObject): String? {
        val raw = meta.opt("imdbRating") ?: meta.opt("rating")
        return when (raw) {
            is Number -> String.format(Locale.US, "%.1f", raw.toDouble())
            is String -> nonBlank(raw)
            else -> null
        }
    }

    private fun parseCachedManifest(raw: String?): JSONObject? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private fun fallbackManifestFor(seed: AddonManifestSeed): JSONObject? {
        val looksLikeCinemeta =
            seed.addonIdHint.contains("cinemeta", ignoreCase = true) ||
                seed.manifestUrl.contains("cinemeta", ignoreCase = true)
        if (!looksLikeCinemeta) {
            return null
        }

        return JSONObject()
            .put("id", "com.linvo.cinemeta")
            .put(
                "catalogs",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("type", "movie")
                            .put("id", "top")
                            .put("name", "Top Movies")
                    )
                    .put(
                        JSONObject()
                            .put("type", "series")
                            .put("id", "top")
                            .put("name", "Top Series")
                    )
            )
    }

    private suspend fun resolveContinueWatchingMeta(
        entry: WatchHistoryEntry,
        mediaType: String,
        resolvedAddons: List<ResolvedAddon>
    ): ContinueWatchingMeta? {
        val lookupIds = lookupIdsForContinueWatching(entry.contentId)
        if (lookupIds.isEmpty()) {
            return null
        }

        for (lookupId in lookupIds) {
            val cached = readCachedContinueWatchingMeta(cacheKey = continueWatchingCacheKey(mediaType, lookupId))
            if (cached != null) {
                return cached
            }
        }

        for (addon in resolvedAddons) {
            for (lookupId in lookupIds) {
                val response =
                    fetchAddonMeta(
                        seed = addon.seed,
                        mediaType = mediaType,
                        lookupId = lookupId
                    ) ?: continue
                val meta = response.optJSONObject("meta") ?: continue
                val resolved =
                    ContinueWatchingMeta(
                        title =
                            nonBlank(meta.optString("name"))
                                ?: nonBlank(meta.optString("title"))
                                ?: nonBlank(entry.title),
                        backdropUrl =
                            nonBlank(meta.optString("background"))
                                ?: nonBlank(meta.optString("poster")),
                        logoUrl = nonBlank(meta.optString("logo")),
                        addonId = addon.addonId
                    )
                if (!resolved.hasDisplayData()) {
                    continue
                }

                for (candidateLookupId in lookupIds) {
                    cacheContinueWatchingMeta(
                        cacheKey = continueWatchingCacheKey(mediaType, candidateLookupId),
                        value = resolved
                    )
                }
                return resolved
            }
        }

        return null
    }

    private suspend fun fetchAddonMeta(seed: AddonManifestSeed, mediaType: String, lookupId: String): JSONObject? {
        return httpGetJson(buildMetaUrl(seed = seed, mediaType = mediaType, lookupId = lookupId))
    }

    private fun buildMetaUrl(seed: AddonManifestSeed, mediaType: String, lookupId: String): String {
        val encodedId = URLEncoder.encode(lookupId, StandardCharsets.UTF_8.name())
        return buildString {
            append(seed.baseUrl.trimEnd('/'))
            append("/meta/")
            append(mediaType)
            append('/')
            append(encodedId)
            append(".json")
            if (!seed.encodedQuery.isNullOrBlank()) {
                append('?')
                append(seed.encodedQuery)
            }
        }
    }

    private fun buildMediaTypesToTry(preferred: String?): List<String> {
        val normalized = preferred?.trim()?.lowercase(Locale.US)
        return when (normalized) {
            "movie" -> listOf("movie", "series")
            "series" -> listOf("series", "movie")
            else -> listOf("movie", "series")
        }
    }

    private fun parseMediaDetails(
        meta: JSONObject,
        fallbackId: String,
        fallbackMediaType: String,
        addonId: String
    ): MediaDetails? {
        val id = nonBlank(meta.optString("id")) ?: fallbackId
        val imdbId =
            normalizeImdbId(
                nonBlank(meta.optString("imdbId"))
                    ?: nonBlank(meta.optString("imdb_id"))
                    ?: nonBlank(meta.optString("imdb"))
            )
        val mediaType =
            nonBlank(meta.optString("type"))?.lowercase(Locale.US)
                ?: fallbackMediaType.lowercase(Locale.US)
        if (mediaType != "movie" && mediaType != "series") {
            return null
        }

        val title = nonBlank(meta.optString("name")) ?: nonBlank(meta.optString("title")) ?: id
        val posterUrl = nonBlank(meta.optString("poster"))
        val backdropUrl = nonBlank(meta.optString("background")) ?: posterUrl
        val logoUrl = nonBlank(meta.optString("logo"))
        val description = nonBlank(meta.optString("description"))
        val genres = readStringList(meta, "genres")
        val runtime = nonBlank(meta.optString("runtime"))
        val year = extractYear(meta)
        val certification =
            nonBlank(meta.optString("certification"))
                ?: nonBlank(meta.optString("mpaaRating"))
                ?: nonBlank(meta.optString("ageRating"))
        val rating = parseMetaRating(meta)
        val cast = readStringList(meta, "cast")
        val directors = readStringList(meta, "director").ifEmpty { readStringList(meta, "directors") }
        val creators = readStringList(meta, "creators").ifEmpty { readStringList(meta, "creator") }
        val videos = parseVideos(meta)

        return MediaDetails(
            id = id,
            imdbId = imdbId,
            mediaType = mediaType,
            title = title,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            logoUrl = logoUrl,
            description = description,
            genres = genres,
            year = year,
            runtime = runtime,
            certification = certification,
            rating = rating,
            cast = cast,
            directors = directors,
            creators = creators,
            videos = videos,
            addonId = addonId
        )
    }

    private fun parseMetaRating(meta: JSONObject): String? {
        val value = meta.opt("imdbRating") ?: meta.opt("rating") ?: return null
        return when (value) {
            is Number -> String.format(Locale.US, "%.1f", value.toDouble())
            else -> nonBlank(value.toString())
        }
    }

    private fun extractYear(meta: JSONObject): String? {
        val yearInt = meta.optInt("year", 0)
        if (yearInt in 1800..2200) {
            return yearInt.toString()
        }

        val candidates =
            listOf(
                nonBlank(meta.optString("releaseInfo")),
                nonBlank(meta.optString("released")),
                nonBlank(meta.optString("releaseDate"))
            ).filterNotNull()

        val regex = Regex("\\b(19\\d{2}|20\\d{2})\\b")
        return candidates.firstNotNullOfOrNull { text ->
            regex.find(text)?.value
        }
    }

    private fun readStringList(meta: JSONObject, key: String): List<String> {
        val value = meta.opt(key) ?: return emptyList()
        return when (value) {
            is JSONArray -> {
                buildList {
                    for (index in 0 until value.length()) {
                        nonBlank(value.optString(index))?.let { add(it) }
                    }
                }
            }

            is String -> listOfNotNull(nonBlank(value))
            else -> emptyList()
        }
    }

    private fun parseVideos(meta: JSONObject): List<MediaVideo> {
        val array = meta.optJSONArray("videos") ?: return emptyList()
        val videos =
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = nonBlank(item.optString("id")) ?: continue
                    val title = nonBlank(item.optString("title")) ?: nonBlank(item.optString("name")) ?: id
                    val season = item.optInt("season", 0).takeIf { it > 0 }
                    val episode = item.optInt("episode", 0).takeIf { it > 0 }
                    val released = nonBlank(item.optString("released"))
                    val overview = nonBlank(item.optString("overview"))
                    val thumbnailUrl =
                        nonBlank(item.optString("thumbnail"))
                            ?: nonBlank(item.optString("poster"))
                            ?: nonBlank(item.optString("background"))
                    add(
                        MediaVideo(
                            id = id,
                            title = title,
                            season = season,
                            episode = episode,
                            released = released,
                            overview = overview,
                            thumbnailUrl = thumbnailUrl
                        )
                    )
                }
            }
        return videos.sortedWith(
            compareBy<MediaVideo> { it.season ?: Int.MAX_VALUE }
                .thenBy { it.episode ?: Int.MAX_VALUE }
                .thenBy { it.title.lowercase(Locale.US) }
        )
    }

    private fun lookupIdsForContinueWatching(contentId: String): List<String> {
        val normalized = contentId.trim()
        if (normalized.isEmpty()) {
            return emptyList()
        }

        val ids = mutableListOf(normalized)
        val parsedEpisodeMatch = EPISODE_SUFFIX_REGEX.matchEntire(normalized)
        if (parsedEpisodeMatch != null) {
            ids += parsedEpisodeMatch.groupValues[1]
        }
        return ids.distinct()
    }

    private fun continueWatchingCacheKey(mediaType: String, contentId: String): String {
        return "${mediaType.lowercase(Locale.US)}:${contentId.lowercase(Locale.US)}"
    }

    private fun readCachedContinueWatchingMeta(cacheKey: String): ContinueWatchingMeta? {
        val cached = continueWatchingMetaCache[cacheKey] ?: return null
        val ageMs = System.currentTimeMillis() - cached.cachedAtEpochMs
        if (ageMs > CONTINUE_WATCHING_META_CACHE_TTL_MS) {
            continueWatchingMetaCache.remove(cacheKey)
            return null
        }
        return cached.meta
    }

    private fun cacheContinueWatchingMeta(cacheKey: String, value: ContinueWatchingMeta) {
        continueWatchingMetaCache[cacheKey] =
            CachedContinueWatchingMeta(
                meta = value,
                cachedAtEpochMs = System.currentTimeMillis()
            )
    }

    private fun fallbackContinueWatchingTitle(entry: WatchHistoryEntry): String {
        val normalizedTitle = nonBlank(entry.title)
        if (normalizedTitle != null) {
            return normalizedTitle
        }
        if (entry.season != null && entry.episode != null) {
            return "${entry.contentId} S${entry.season} E${entry.episode}"
        }
        return entry.contentId
    }

    private fun continueWatchingKey(entry: WatchHistoryEntry): String {
        val seasonPart = entry.season?.toString() ?: "-"
        val episodePart = entry.episode?.toString() ?: "-"
        return "${entry.contentType.name.lowercase(Locale.US)}:${entry.contentId}:$seasonPart:$episodePart"
    }

    private data class ResolvedAddon(
        val orderIndex: Int,
        val seed: AddonManifestSeed,
        val addonId: String,
        val manifest: JSONObject?
    )

    private data class CatalogCandidate(
        val addonOrderIndex: Int,
        val addonId: String,
        val seed: AddonManifestSeed,
        val catalogId: String,
        val catalogType: String,
        val name: String,
        val priority: Int
    )

    private data class ContinueWatchingMeta(
        val title: String?,
        val backdropUrl: String?,
        val logoUrl: String?,
        val addonId: String?
    ) {
        fun hasDisplayData(): Boolean {
            return !title.isNullOrBlank() || !backdropUrl.isNullOrBlank() || !logoUrl.isNullOrBlank()
        }
    }

    private data class CachedContinueWatchingMeta(
        val meta: ContinueWatchingMeta,
        val cachedAtEpochMs: Long
    )

    private suspend fun httpGetJson(url: String): JSONObject? {
        return withContext(Dispatchers.IO) {
            val response =
                runCatching {
                    httpClient.get(
                        url = url.toHttpUrl(),
                        headers = Headers.headersOf("Accept", "application/json"),
                        callTimeoutMs = 12_000L,
                    )
                }.getOrNull() ?: return@withContext null

            if (response.code !in 200..299) {
                return@withContext null
            }
            val body = response.body
            if (body.isBlank()) {
                return@withContext null
            }
            runCatching { JSONObject(body) }.getOrNull()
        }
    }

    companion object {
        private const val TAG = "HomeCatalogService"
        private val EPISODE_SUFFIX_REGEX = Regex("^(.*):(\\d+):(\\d+)$")
        private const val CONTINUE_WATCHING_META_CACHE_TTL_MS = 5 * 60 * 1000L
        private const val RESOLVED_ADDONS_CACHE_TTL_MS = 60_000L
    }
}

private fun nonBlank(value: String?): String? {
    val trimmed = value?.trim()
    return if (trimmed.isNullOrEmpty()) null else trimmed
}

private fun normalizeImdbId(value: String?): String? {
    val trimmed = nonBlank(value) ?: return null
    val normalized = trimmed.lowercase(Locale.US)
    return if (normalized.startsWith("tt") && normalized.length >= 4) normalized else null
}

private fun WatchHistoryEntry.asCatalogMediaType(): String {
    return if (contentType == MetadataLabMediaType.SERIES) "series" else "movie"
}

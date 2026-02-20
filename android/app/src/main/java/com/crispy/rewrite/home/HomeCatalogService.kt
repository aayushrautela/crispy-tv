package com.crispy.rewrite.home

import android.content.Context
import com.crispy.rewrite.catalog.CatalogItem
import com.crispy.rewrite.catalog.CatalogPageResult
import com.crispy.rewrite.catalog.CatalogSectionRef
import com.crispy.rewrite.catalog.DiscoverCatalogRef
import com.crispy.rewrite.domain.catalog.CatalogFilter
import com.crispy.rewrite.domain.catalog.CatalogRequestInput
import com.crispy.rewrite.domain.catalog.buildCatalogRequestUrls
import com.crispy.rewrite.metadata.AddonManifestSeed
import com.crispy.rewrite.metadata.MetadataAddonRegistry
import com.crispy.rewrite.player.MetadataLabMediaType
import com.crispy.rewrite.player.WatchHistoryEntry
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

data class HomeHeroItem(
    val id: String,
    val title: String,
    val description: String,
    val rating: String?,
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
    val backdropUrl: String?,
    val logoUrl: String?,
    val addonId: String?,
    val type: String
)

data class ContinueWatchingLoadResult(
    val items: List<ContinueWatchingItem> = emptyList(),
    val statusMessage: String = ""
)

class HomeCatalogService(
    context: Context,
    addonManifestUrlsCsv: String
) {
    private val addonRegistry = MetadataAddonRegistry(context.applicationContext, addonManifestUrlsCsv)
    private val continueWatchingMetaCache = mutableMapOf<String, CachedContinueWatchingMeta>()

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

        candidates.forEach { candidate ->
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

            urls.forEach { url ->
                val response = httpGetJson(url) ?: return@forEach
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
        val items = dedupedEntries.map { entry ->
            val mediaType = entry.asCatalogMediaType()
            val resolvedMeta = resolveContinueWatchingMeta(entry, mediaType, resolvedAddons)

            ContinueWatchingItem(
                id = continueWatchingKey(entry),
                contentId = entry.contentId,
                title = resolvedMeta?.title ?: fallbackContinueWatchingTitle(entry),
                season = entry.season,
                episode = entry.episode,
                watchedAtEpochMs = entry.watchedAtEpochMs,
                backdropUrl = resolvedMeta?.backdropUrl,
                logoUrl = resolvedMeta?.logoUrl,
                addonId = resolvedMeta?.addonId,
                type = mediaType
            )
        }

        return ContinueWatchingLoadResult(
            items = items,
            statusMessage = "Loaded continue watching from watch history."
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

        urls.forEachIndexed { index, url ->
            attemptedUrls += url
            val response = httpGetJson(url) ?: return@forEachIndexed
            val items =
                parseCatalogItems(
                    metas = response.optJSONArray("metas"),
                    addonId = section.addonId,
                    mediaType = section.mediaType
                )

            if (items.isEmpty() && index < urls.lastIndex) {
                return@forEachIndexed
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

    private fun resolveAddons(): List<ResolvedAddon> {
        val seeds = addonRegistry.orderedSeeds()
        if (seeds.isEmpty()) {
            return emptyList()
        }

        return seeds.mapIndexed { index, seed ->
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
        }
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

            items +=
                HomeHeroItem(
                    id = id,
                    title = title,
                    description = description,
                    rating = rating,
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

    private fun resolveContinueWatchingMeta(
        entry: WatchHistoryEntry,
        mediaType: String,
        resolvedAddons: List<ResolvedAddon>
    ): ContinueWatchingMeta? {
        val lookupIds = lookupIdsForContinueWatching(entry.contentId)
        if (lookupIds.isEmpty()) {
            return null
        }

        lookupIds.forEach { lookupId ->
            readCachedContinueWatchingMeta(cacheKey = continueWatchingCacheKey(mediaType, lookupId))?.let { cached ->
                return cached
            }
        }

        resolvedAddons.forEach { addon ->
            lookupIds.forEach { lookupId ->
                val response = fetchAddonMeta(
                    seed = addon.seed,
                    mediaType = mediaType,
                    lookupId = lookupId
                ) ?: return@forEach
                val meta = response.optJSONObject("meta") ?: return@forEach
                val resolved = ContinueWatchingMeta(
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
                    return@forEach
                }

                lookupIds.forEach { candidateLookupId ->
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

    private fun fetchAddonMeta(seed: AddonManifestSeed, mediaType: String, lookupId: String): JSONObject? {
        val encodedId = URLEncoder.encode(lookupId, StandardCharsets.UTF_8.name())
        val url = buildString {
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
        return httpGetJson(url)
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

    companion object {
        private val EPISODE_SUFFIX_REGEX = Regex("^(.*):(\\d+):(\\d+)$")
        private const val CONTINUE_WATCHING_META_CACHE_TTL_MS = 5 * 60 * 1000L
    }
}

private fun httpGetJson(url: String): JSONObject? {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 10_000
        requestMethod = "GET"
        setRequestProperty("Accept", "application/json")
    }

    return runCatching {
        connection.inputStream.bufferedReader().use { reader ->
            val payload = reader.readText()
            if (payload.isBlank()) {
                null
            } else {
                JSONObject(payload)
            }
        }
    }.getOrNull().also {
        connection.disconnect()
    }
}

private fun nonBlank(value: String?): String? {
    val trimmed = value?.trim()
    return if (trimmed.isNullOrEmpty()) null else trimmed
}

private fun WatchHistoryEntry.asCatalogMediaType(): String {
    return if (contentType == MetadataLabMediaType.SERIES) "series" else "movie"
}

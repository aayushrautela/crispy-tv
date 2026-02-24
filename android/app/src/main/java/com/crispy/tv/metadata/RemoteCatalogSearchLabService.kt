package com.crispy.tv.metadata

import android.content.Context
import com.crispy.tv.domain.catalog.AddonSearchResult
import com.crispy.tv.domain.catalog.CatalogFilter
import com.crispy.tv.domain.catalog.CatalogRequestInput
import com.crispy.tv.domain.catalog.SearchMetaInput
import com.crispy.tv.domain.catalog.buildCatalogRequestUrls
import com.crispy.tv.domain.catalog.mergeSearchResults
import com.crispy.tv.player.CatalogLabCatalog
import com.crispy.tv.player.CatalogLabItem
import com.crispy.tv.player.CatalogLabResult
import com.crispy.tv.player.CatalogPageRequest
import com.crispy.tv.player.CatalogSearchLabService
import com.crispy.tv.player.CatalogSearchRequest
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.network.CrispyHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

class RemoteCatalogSearchLabService(
    context: Context,
    addonManifestUrlsCsv: String,
    private val httpClient: CrispyHttpClient,
) : CatalogSearchLabService {
    private val addonRegistry = MetadataAddonRegistry(context.applicationContext, addonManifestUrlsCsv)

    override suspend fun fetchCatalogPage(request: CatalogPageRequest): CatalogLabResult {
        val mediaType = request.mediaType.toCatalogType()
        val catalogId = request.catalogId.trim()
        val resolvedAddons = resolveAddons()
        val mediaCatalogEntries = catalogEntries(resolvedAddons, mediaType)
        val allCatalogs = mediaCatalogEntries.map { it.toPublicCatalog() }
        if (resolvedAddons.isEmpty()) {
            return CatalogLabResult(
                catalogs = allCatalogs,
                statusMessage = "No installed addons available."
            )
        }

        if (allCatalogs.isEmpty()) {
            return CatalogLabResult(
                catalogs = allCatalogs,
                statusMessage = "No '$mediaType' catalogs found in installed addons."
            )
        }

        if (catalogId.isEmpty()) {
            return CatalogLabResult(
                catalogs = allCatalogs,
                statusMessage =
                    "Discovered ${allCatalogs.size} addon catalogs. " +
                        "Set Catalog ID from the list and tap Load Catalog."
            )
        }

        val preferredAddonId = request.preferredAddonId?.trim()?.takeIf { it.isNotEmpty() }
        val candidates =
            mediaCatalogEntries
                .filter { entry -> entry.catalogId.equals(catalogId, ignoreCase = true) }
                .sortedWith(compareBy<CatalogEntry>({ sourceRank(it.addonId, preferredAddonId) }, { it.addonOrderIndex }))

        if (candidates.isEmpty()) {
            return CatalogLabResult(
                catalogs = allCatalogs,
                statusMessage =
                    "No addon catalog found for id '$catalogId' and type '$mediaType'. " +
                        "Pick one from the listed addon catalogs."
            )
        }

        val attemptedUrls = mutableListOf<String>()
        for (entry in candidates) {
            val urls =
                buildCatalogRequestUrls(
                    CatalogRequestInput(
                        baseUrl = entry.seed.baseUrl,
                        mediaType = mediaType,
                        catalogId = entry.catalogId,
                        page = request.page,
                        pageSize = request.pageSize,
                        filters = emptyList(),
                        encodedAddonQuery = entry.seed.encodedQuery
                    )
                )

            for ((attemptIndex, url) in urls.withIndex()) {
                attemptedUrls += url
                val response = httpGetJson(url) ?: continue
                val items = parseCatalogItems(response.optJSONArray("metas"), entry.addonId, mediaType)
                val isSimpleAttempt = request.page <= 1 && attemptIndex == 0
                if (isSimpleAttempt && items.isEmpty() && urls.size > 1) {
                    continue
                }

                return CatalogLabResult(
                    catalogs = allCatalogs,
                    items = items,
                    attemptedUrls = attemptedUrls,
                    statusMessage =
                        if (items.isEmpty()) "No items found." else ""
                )
            }
        }

        return CatalogLabResult(
            catalogs = allCatalogs,
            attemptedUrls = attemptedUrls,
            statusMessage = "Catalog request failed for all matching addons."
        )
    }

    override suspend fun search(request: CatalogSearchRequest): CatalogLabResult {
        val mediaType = request.mediaType.toCatalogType()
        val query = request.query.trim()
        if (query.isEmpty()) {
            return CatalogLabResult(statusMessage = "Search query is required.")
        }

        val resolvedAddons = resolveAddons()
        val mediaCatalogEntries = catalogEntries(resolvedAddons, mediaType)
        val allCatalogs = mediaCatalogEntries.map { it.toPublicCatalog() }
        if (resolvedAddons.isEmpty()) {
            return CatalogLabResult(
                catalogs = allCatalogs,
                statusMessage = "No installed addons available."
            )
        }

        val searchableCatalogsByAddon =
            mediaCatalogEntries
                .filter { entry -> entry.supportsSearch }
                .groupBy { entry -> entry.addonOrderIndex }

        if (searchableCatalogsByAddon.isEmpty()) {
            return CatalogLabResult(
                catalogs = allCatalogs,
                statusMessage = "No searchable '$mediaType' catalogs found in installed addons."
            )
        }

        val preferredAddonId = request.preferredAddonId?.trim()?.takeIf { it.isNotEmpty() }
        val orderedAddons =
            resolvedAddons
                .sortedWith(
                    compareBy<ResolvedAddon>(
                        { sourceRank(it.addonId, preferredAddonId) },
                        { it.orderIndex }
                    )
                )

        val attemptedUrls = mutableListOf<String>()
        val addonResults = mutableListOf<AddonSearchResult>()

        for (addon in orderedAddons) {
            val addonSearchCatalogs = searchableCatalogsByAddon[addon.orderIndex] ?: continue
            if (addonSearchCatalogs.isEmpty()) {
                continue
            }

            val addonMetasById = linkedMapOf<String, SearchMetaInput>()
            for (searchCatalog in addonSearchCatalogs) {
                val urls =
                    buildCatalogRequestUrls(
                        CatalogRequestInput(
                            baseUrl = addon.seed.baseUrl,
                            mediaType = mediaType,
                            catalogId = searchCatalog.catalogId,
                            page = request.page,
                            pageSize = request.pageSize,
                            filters = listOf(CatalogFilter(key = "search", value = query)),
                            encodedAddonQuery = addon.seed.encodedQuery
                        )
                    )

                var catalogMetas: List<SearchMetaInput>? = null
                for (url in urls) {
                    attemptedUrls += url
                    val response = httpGetJson(url) ?: continue
                    catalogMetas = parseSearchMetas(response.optJSONArray("metas"))
                    break
                }

                catalogMetas?.forEach { meta ->
                    addonMetasById.putIfAbsent(meta.id, meta)
                }
            }

            if (addonMetasById.isNotEmpty()) {
                addonResults += AddonSearchResult(addonId = addon.addonId, metas = addonMetasById.values.toList())
            }
        }

        val merged = mergeSearchResults(addonResults, preferredAddonId)
        val items = merged.map { meta ->
            CatalogLabItem(
                id = meta.id,
                title = meta.title,
                addonId = meta.addonId,
                type = mediaType
            )
        }

        return CatalogLabResult(
            catalogs = allCatalogs,
            items = items,
            attemptedUrls = attemptedUrls,
            statusMessage =
                "Search '$query' returned ${items.size} unique items from ${addonResults.size} addons."
        )
    }

    private suspend fun resolveAddons(): List<ResolvedAddon> {
        val seeds = addonRegistry.orderedSeeds()
        if (seeds.isEmpty()) {
            return emptyList()
        }

        val resolved = mutableListOf<ResolvedAddon>()
        for ((index, seed) in seeds.withIndex()) {
            val networkManifest = httpGetJson(seed.manifestUrl)
            if (networkManifest != null) {
                addonRegistry.cacheManifest(seed, networkManifest)
            }

            val manifest = networkManifest ?: parseCachedManifest(seed.cachedManifestJson) ?: fallbackManifestFor(seed)
            val addonId =
                nonBlank(manifest?.optString("id"))
                    ?: seed.addonIdHint

            resolved +=
                ResolvedAddon(
                    orderIndex = index,
                    seed = seed,
                    addonId = addonId,
                    manifest = manifest
                )
        }
        return resolved
    }

    private fun catalogEntries(resolvedAddons: List<ResolvedAddon>, mediaType: String): List<CatalogEntry> {
        val entries = mutableListOf<CatalogEntry>()
        resolvedAddons.forEach { addon ->
            val manifest = addon.manifest ?: return@forEach
            val catalogs = manifest.optJSONArray("catalogs") ?: return@forEach
            for (index in 0 until catalogs.length()) {
                val catalog = catalogs.optJSONObject(index) ?: continue
                val type = nonBlank(catalog.optString("type")) ?: continue
                if (!type.equals(mediaType, ignoreCase = true)) {
                    continue
                }
                val id = nonBlank(catalog.optString("id")) ?: continue
                val name = nonBlank(catalog.optString("name")) ?: id
                entries += CatalogEntry(
                    addonOrderIndex = addon.orderIndex,
                    addonId = addon.addonId,
                    seed = addon.seed,
                    catalogId = id,
                    catalogType = type.lowercase(),
                    name = name,
                    supportsSearch = supportsSearch(catalog)
                )
            }
        }
        return entries
    }

    private fun supportsSearch(catalog: JSONObject): Boolean {
        val extraSupported = parseStringArray(catalog.optJSONArray("extraSupported"))
        if (extraSupported.any { value -> value.equals("search", ignoreCase = true) }) {
            return true
        }

        val extra = catalog.optJSONArray("extra") ?: return false
        for (index in 0 until extra.length()) {
            when (val value = extra.opt(index)) {
                is String -> if (value.equals("search", ignoreCase = true)) return true
                is JSONObject -> {
                    if (value.optString("name").equals("search", ignoreCase = true)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun parseCatalogItems(metas: JSONArray?, addonId: String, mediaType: String): List<CatalogLabItem> {
        if (metas == null) {
            return emptyList()
        }

        val items = mutableListOf<CatalogLabItem>()
        for (index in 0 until metas.length()) {
            val meta = metas.optJSONObject(index) ?: continue
            val id = nonBlank(meta.optString("id")) ?: continue
            val title =
                nonBlank(meta.optString("name"))
                    ?: nonBlank(meta.optString("title"))
                    ?: id
            val type = nonBlank(meta.optString("type")) ?: mediaType
            items += CatalogLabItem(id = id, title = title, addonId = addonId, type = type)
        }
        return items
    }

    private fun parseSearchMetas(metas: JSONArray?): List<SearchMetaInput> {
        if (metas == null) {
            return emptyList()
        }

        val values = mutableListOf<SearchMetaInput>()
        for (index in 0 until metas.length()) {
            val meta = metas.optJSONObject(index) ?: continue
            val id = nonBlank(meta.optString("id")) ?: continue
            val title =
                nonBlank(meta.optString("name"))
                    ?: nonBlank(meta.optString("title"))
                    ?: id
            values += SearchMetaInput(id = id, title = title)
        }
        return values
    }

    private fun parseCachedManifest(raw: String?): JSONObject? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return runCatching { JSONObject(raw) }.getOrNull()
    }

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

    private fun fallbackManifestFor(seed: AddonManifestSeed): JSONObject? {
        val looksLikeCinemeta =
            seed.addonIdHint.contains("cinemeta", ignoreCase = true) ||
                seed.manifestUrl.contains("cinemeta", ignoreCase = true)
        if (looksLikeCinemeta) {
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

        return null
    }

    private data class ResolvedAddon(
        val orderIndex: Int,
        val seed: AddonManifestSeed,
        val addonId: String,
        val manifest: JSONObject?
    )

    private data class CatalogEntry(
        val addonOrderIndex: Int,
        val addonId: String,
        val seed: AddonManifestSeed,
        val catalogId: String,
        val catalogType: String,
        val name: String,
        val supportsSearch: Boolean
    ) {
        fun toPublicCatalog(): CatalogLabCatalog {
            return CatalogLabCatalog(
                addonId = addonId,
                catalogType = catalogType,
                catalogId = catalogId,
                name = name,
                supportsSearch = supportsSearch
            )
        }
    }
}

private fun sourceRank(addonId: String, preferredAddonId: String?): Int {
    if (preferredAddonId != null && addonId.equals(preferredAddonId, ignoreCase = true)) {
        return 0
    }
    if (addonId.contains("cinemeta", ignoreCase = true)) {
        return 1
    }
    return 2
}

private fun parseStringArray(value: JSONArray?): List<String> {
    if (value == null) {
        return emptyList()
    }
    return buildList {
        for (index in 0 until value.length()) {
            val raw = value.optString(index)
            if (raw.isNotBlank()) {
                add(raw)
            }
        }
    }
}

private fun nonBlank(value: String?): String? {
    val trimmed = value?.trim()
    return if (trimmed.isNullOrEmpty()) null else trimmed
}

private fun MetadataLabMediaType.toCatalogType(): String {
    return if (this == MetadataLabMediaType.SERIES) "series" else "movie"
}

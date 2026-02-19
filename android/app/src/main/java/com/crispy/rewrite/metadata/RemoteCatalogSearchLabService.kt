package com.crispy.rewrite.metadata

import android.content.Context
import com.crispy.rewrite.domain.catalog.AddonSearchResult
import com.crispy.rewrite.domain.catalog.CatalogFilter
import com.crispy.rewrite.domain.catalog.CatalogRequestInput
import com.crispy.rewrite.domain.catalog.SearchMetaInput
import com.crispy.rewrite.domain.catalog.buildCatalogRequestUrls
import com.crispy.rewrite.domain.catalog.mergeSearchResults
import com.crispy.rewrite.player.CatalogLabCatalog
import com.crispy.rewrite.player.CatalogLabItem
import com.crispy.rewrite.player.CatalogLabResult
import com.crispy.rewrite.player.CatalogPageRequest
import com.crispy.rewrite.player.CatalogSearchLabService
import com.crispy.rewrite.player.CatalogSearchRequest
import com.crispy.rewrite.player.MetadataLabMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class RemoteCatalogSearchLabService(
    context: Context,
    addonManifestUrlsCsv: String
) : CatalogSearchLabService {
    private val addonRegistry = MetadataAddonRegistry(context.applicationContext, addonManifestUrlsCsv)

    override suspend fun fetchCatalogPage(request: CatalogPageRequest): CatalogLabResult {
        val mediaType = request.mediaType.toCatalogType()
        val catalogId = request.catalogId.trim()
        if (catalogId.isEmpty()) {
            return CatalogLabResult(statusMessage = "Catalog ID is required.")
        }

        val resolvedAddons = resolveAddons()
        val allCatalogs = catalogEntries(resolvedAddons, mediaType).map { it.toPublicCatalog() }
        if (resolvedAddons.isEmpty()) {
            return CatalogLabResult(
                catalogs = allCatalogs,
                statusMessage = "No installed addons available."
            )
        }

        val preferredAddonId = request.preferredAddonId?.trim()?.takeIf { it.isNotEmpty() }
        val candidates =
            catalogEntries(resolvedAddons, mediaType)
                .filter { entry -> entry.catalogId.equals(catalogId, ignoreCase = true) }
                .sortedWith(compareBy<CatalogEntry>({ sourceRank(it.addonId, preferredAddonId) }, { it.addonOrderIndex }))

        if (candidates.isEmpty()) {
            return CatalogLabResult(
                catalogs = allCatalogs,
                statusMessage = "No addon catalog found for id '$catalogId' and type '$mediaType'."
            )
        }

        val attemptedUrls = mutableListOf<String>()
        candidates.forEach { entry ->
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

            urls.forEachIndexed { attemptIndex, url ->
                attemptedUrls += url
                val response = httpGetJson(url) ?: return@forEachIndexed
                val items = parseCatalogItems(response.optJSONArray("metas"), entry.addonId, mediaType)
                val isSimpleAttempt = request.page <= 1 && attemptIndex == 0
                if (isSimpleAttempt && items.isEmpty() && urls.size > 1) {
                    return@forEachIndexed
                }

                return CatalogLabResult(
                    catalogs = allCatalogs,
                    items = items,
                    attemptedUrls = attemptedUrls,
                    statusMessage =
                        "Loaded ${items.size} items from ${entry.addonId} (${entry.catalogId})."
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
        val allCatalogs = catalogEntries(resolvedAddons, mediaType).map { it.toPublicCatalog() }
        if (resolvedAddons.isEmpty()) {
            return CatalogLabResult(
                catalogs = allCatalogs,
                statusMessage = "No installed addons available."
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

        orderedAddons.forEach { addon ->
            val searchCatalog =
                catalogEntries(listOf(addon), mediaType)
                    .firstOrNull { entry -> entry.supportsSearch }
                ?: return@forEach

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

            var addonMetas: List<SearchMetaInput>? = null
            for (url in urls) {
                attemptedUrls += url
                val response = httpGetJson(url) ?: continue
                val metas = parseSearchMetas(response.optJSONArray("metas"))
                addonMetas = metas
                break
            }

            addonMetas?.let { metas ->
                addonResults += AddonSearchResult(addonId = addon.addonId, metas = metas)
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
            val addonId =
                nonBlank(manifest?.optString("id"))
                    ?: seed.addonIdHint

            ResolvedAddon(
                orderIndex = index,
                seed = seed,
                addonId = addonId,
                manifest = manifest
            )
        }
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

private fun MetadataLabMediaType.toCatalogType(): String {
    return if (this == MetadataLabMediaType.SERIES) "series" else "movie"
}

package com.crispy.rewrite.home

import android.content.Context
import com.crispy.rewrite.domain.catalog.CatalogRequestInput
import com.crispy.rewrite.domain.catalog.buildCatalogRequestUrls
import com.crispy.rewrite.metadata.AddonManifestSeed
import com.crispy.rewrite.metadata.MetadataAddonRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
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

class HomeCatalogService(
    context: Context,
    addonManifestUrlsCsv: String
) {
    private val addonRegistry = MetadataAddonRegistry(context.applicationContext, addonManifestUrlsCsv)

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

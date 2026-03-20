package com.crispy.tv.home

import android.content.Context
import com.crispy.tv.domain.metadata.formatIdForIdPrefixes
import com.crispy.tv.domain.metadata.normalizeNuvioMediaId
import com.crispy.tv.metadata.AddonManifestSeed
import com.crispy.tv.metadata.MetadataAddonRegistry
import com.crispy.tv.network.CrispyHttpClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

internal class CalendarMetaEpisodeService(
    context: Context,
    addonManifestUrlsCsv: String,
    private val httpClient: CrispyHttpClient,
) {
    private val addonRegistry = MetadataAddonRegistry(context.applicationContext, addonManifestUrlsCsv)

    suspend fun getUpcomingEpisodes(
        seriesId: String,
        nowMs: Long,
        daysBack: Int,
        daysAhead: Int,
        maxEpisodes: Int,
        preferredAddonId: String? = null,
    ): SeriesMetaEpisodes? {
        val metadata = getMetaDetails(type = "series", id = seriesId, preferredAddonId = preferredAddonId) ?: return null
        val startMs = nowMs - daysBack.coerceAtLeast(0) * DAY_MS
        val endMs = nowMs + daysAhead.coerceAtLeast(0) * DAY_MS

        val episodes =
            metadata.videos
                .mapNotNull { video ->
                    val releasedAtMs = parseReleaseToEpochMs(video.released) ?: return@mapNotNull null
                    if (releasedAtMs < startMs || releasedAtMs > endMs) return@mapNotNull null
                    video.copy(releasedAtMs = releasedAtMs)
                }
                .sortedBy { it.releasedAtMs }
                .take(maxEpisodes.coerceAtLeast(1))

        return SeriesMetaEpisodes(
            seriesName = metadata.seriesName,
            posterUrl = metadata.posterUrl,
            backdropUrl = metadata.backdropUrl,
            addonId = metadata.addonId,
            episodes = episodes,
        )
    }

    private suspend fun getMetaDetails(
        type: String,
        id: String,
        preferredAddonId: String?,
    ): MetaDetails? {
        val normalizedId = normalizeNuvioMediaId(id).addonLookupId.ifBlank { normalizeNuvioMediaId(id).contentId }
        if (normalizedId.isBlank()) return null

        val endpoints = resolveEndpoints()
        val preferred = preferredAddonId?.trim()?.takeIf { it.isNotEmpty() }
        val preferredEndpoint = preferred?.let { addonId -> endpoints.firstOrNull { it.addonId.equals(addonId, ignoreCase = true) } }

        if (preferredEndpoint != null) {
            fetchMetaFromEndpoint(preferredEndpoint, type, normalizedId)?.let { return it }
        }

        fetchMetaFromCinemeta(type, normalizedId)?.let { return it }

        for (endpoint in endpoints) {
            if (endpoint === preferredEndpoint) continue
            if (endpoint.addonId.equals(CINEMETA_ADDON_ID, ignoreCase = true)) continue
            fetchMetaFromEndpoint(endpoint, type, normalizedId)?.let { return it }
        }

        return null
    }

    private suspend fun resolveEndpoints(): List<AddonMetaEndpoint> {
        val endpoints = mutableListOf<AddonMetaEndpoint>()
        for (seed in addonRegistry.orderedSeeds()) {
            parseEndpoint(seed)?.let(endpoints::add)
        }
        return endpoints
    }

    private suspend fun parseEndpoint(seed: AddonManifestSeed): AddonMetaEndpoint? {
        val manifest =
            httpClient.getJsonObject(seed.manifestUrl)?.also { addonRegistry.cacheManifest(seed, it) }
                ?: parseJsonObject(seed.cachedManifestJson)
                ?: fallbackManifest(seed)
                ?: return null

        val addonId = nonBlank(manifest.optString("id")) ?: seed.addonIdHint
        val addonIdPrefixes =
            parseStringArray(manifest.optJSONArray("idPrefixes")).ifEmpty {
                nonBlank(manifest.optString("idPrefix"))?.let(::listOf).orEmpty()
            }

        val resources = manifest.optJSONArray("resources") ?: JSONArray()
        var declaredTypes = emptyList<String>()
        var resourceIdPrefixes = emptyList<String>()

        for (index in 0 until resources.length()) {
            when (val resource = resources.opt(index)) {
                is String -> {
                    if (!resource.equals("meta", ignoreCase = true)) continue
                    declaredTypes = listOf("movie", "series")
                }

                is JSONObject -> {
                    if (!resource.optString("name").equals("meta", ignoreCase = true)) continue
                    declaredTypes = parseStringArray(resource.optJSONArray("types"))
                    resourceIdPrefixes =
                        parseStringArray(resource.optJSONArray("idPrefixes")).ifEmpty {
                            nonBlank(resource.optString("idPrefix"))?.let(::listOf).orEmpty()
                        }
                    break
                }
            }
        }

        if (declaredTypes.isEmpty()) {
            return null
        }

        return AddonMetaEndpoint(
            addonId = addonId,
            baseUrl = seed.baseUrl,
            encodedQuery = seed.encodedQuery,
            declaredTypes = declaredTypes,
            addonIdPrefixes = addonIdPrefixes,
            resourceIdPrefixes = resourceIdPrefixes,
        )
    }

    private suspend fun fetchMetaFromEndpoint(
        endpoint: AddonMetaEndpoint,
        type: String,
        id: String,
    ): MetaDetails? {
        val lookupId = endpoint.formatLookupId(type, id) ?: return null
        val resolvedType = endpoint.resolveType(type)
        val encodedId = URLEncoder.encode(lookupId, StandardCharsets.UTF_8.name())
        val url = buildString {
            append(endpoint.baseUrl.trimEnd('/'))
            append("/meta/")
            append(resolvedType)
            append('/')
            append(encodedId)
            append(".json")
            if (!endpoint.encodedQuery.isNullOrBlank()) {
                append('?')
                append(endpoint.encodedQuery)
            }
        }
        val response = httpClient.getJsonObject(url) ?: return null
        return parseMetaDetails(response.optJSONObject("meta"), endpoint.addonId)
    }

    private suspend fun fetchMetaFromCinemeta(type: String, id: String): MetaDetails? {
        val encodedId = URLEncoder.encode(id, StandardCharsets.UTF_8.name())
        for (baseUrl in CINEMETA_BASE_URLS) {
            val url = "${baseUrl.trimEnd('/')}/meta/${type.lowercase()}/$encodedId.json"
            val response = httpClient.getJsonObject(url) ?: continue
            parseMetaDetails(response.optJSONObject("meta"), CINEMETA_ADDON_ID)?.let { return it }
        }
        return null
    }

    private fun parseMetaDetails(meta: JSONObject?, addonId: String): MetaDetails? {
        if (meta == null) return null
        val name = nonBlank(meta.optString("name")) ?: return null
        val videosArray = meta.optJSONArray("videos") ?: JSONArray()
        val videos = buildList {
            for (index in 0 until videosArray.length()) {
                val video = videosArray.optJSONObject(index) ?: continue
                val season = video.optInt("season", 0).takeIf { it >= 0 } ?: 0
                val episode = video.optInt("episode", 0).takeIf { it >= 0 } ?: 0
                add(
                    MetaVideo(
                        id = nonBlank(video.optString("id")) ?: "$season:$episode",
                        title = nonBlank(video.optString("title")),
                        season = season,
                        episode = episode,
                        released = nonBlank(video.optString("released")),
                        overview = nonBlank(video.optString("overview")),
                        thumbnailUrl = firstNonBlank(
                            video.optString("thumbnail"),
                            video.optString("thumbnailUrl"),
                        ),
                    )
                )
            }
        }
        return MetaDetails(
            seriesName = name,
            posterUrl = firstNonBlank(meta.optString("poster"), meta.optString("posterShape")),
            backdropUrl = firstNonBlank(meta.optString("background"), meta.optString("poster")),
            addonId = addonId,
            videos = videos,
        )
    }

    private fun parseStringArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                nonBlank(array.optString(index))?.let(::add)
            }
        }
    }

    private fun parseJsonObject(raw: String?): JSONObject? {
        if (raw.isNullOrBlank()) return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private fun fallbackManifest(seed: AddonManifestSeed): JSONObject? {
        if (
            seed.addonIdHint.contains("cinemeta", ignoreCase = true) ||
                seed.manifestUrl.contains("cinemeta", ignoreCase = true)
        ) {
            return JSONObject()
                .put("id", CINEMETA_ADDON_ID)
                .put(
                    "resources",
                    JSONArray().put(
                        JSONObject()
                            .put("name", "meta")
                            .put("types", JSONArray().put("movie").put("series"))
                            .put("idPrefixes", JSONArray().put("tt"))
                    )
                )
        }
        return null
    }

    private fun parseReleaseToEpochMs(value: String?): Long? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return runCatching { java.time.Instant.parse(raw).toEpochMilli() }
            .recoverCatching {
                java.time.LocalDate.parse(raw.take(10))
                    .atStartOfDay(java.time.ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli()
            }
            .getOrNull()
    }

    private fun nonBlank(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    private fun firstNonBlank(vararg values: String?): String? = values.firstNotNullOfOrNull { nonBlank(it) }

    private data class AddonMetaEndpoint(
        val addonId: String,
        val baseUrl: String,
        val encodedQuery: String?,
        val declaredTypes: List<String>,
        val addonIdPrefixes: List<String>,
        val resourceIdPrefixes: List<String>,
    ) {
        fun resolveType(requestedType: String): String {
            return declaredTypes.firstOrNull { it.equals(requestedType, ignoreCase = true) }
                ?: declaredTypes.firstOrNull()
                ?: requestedType.lowercase()
        }

        fun formatLookupId(requestedType: String, rawId: String): String? {
            val normalized = normalizeNuvioMediaId(rawId).addonLookupId.ifBlank { normalizeNuvioMediaId(rawId).contentId }
            val prefixes = resourceIdPrefixes.ifEmpty { addonIdPrefixes }
            return if (prefixes.isNotEmpty()) {
                formatIdForIdPrefixes(
                    input = normalized,
                    mediaType = if (requestedType.equals("movie", ignoreCase = true)) "movie" else "series",
                    idPrefixes = prefixes,
                )
            } else {
                normalized.takeIf { it.isNotBlank() }
            }
        }
    }

    private data class MetaDetails(
        val seriesName: String,
        val posterUrl: String?,
        val backdropUrl: String?,
        val addonId: String,
        val videos: List<MetaVideo>,
    )

    private companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
        private const val CINEMETA_ADDON_ID = "com.linvo.cinemeta"
        private val CINEMETA_BASE_URLS = listOf(
            "https://v3-cinemeta.strem.io",
            "http://v3-cinemeta.strem.io",
        )
    }
}

internal data class SeriesMetaEpisodes(
    val seriesName: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val addonId: String?,
    val episodes: List<MetaVideo>,
)

internal data class MetaVideo(
    val id: String,
    val title: String?,
    val season: Int,
    val episode: Int,
    val released: String?,
    val overview: String?,
    val thumbnailUrl: String?,
    val releasedAtMs: Long = 0L,
)

private suspend fun CrispyHttpClient.getJsonObject(url: String): JSONObject? {
    val httpUrl = url.toHttpUrlOrNull() ?: return null
    val response = runCatching { get(httpUrl) }.getOrNull() ?: return null
    if (response.code !in 200..299) return null
    return runCatching { JSONObject(response.body) }.getOrNull()
}

package com.crispy.tv.home

import android.content.Context
import com.crispy.tv.addons.registry.AddonManifestSeed
import com.crispy.tv.addons.registry.MetadataAddonRegistry
import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.addons.lookup.parseLookupId
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import com.crispy.tv.library.optIntOrNull
import com.crispy.tv.library.optJsonArray
import com.crispy.tv.library.optJsonObject
import com.crispy.tv.library.optStringOrEmpty
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal class CalendarMetaEpisodeService(
    context: Context,
    private val httpClient: CrispyHttpClient,
) {
    private val addonRegistry = MetadataAddonRegistry(context.applicationContext)

    suspend fun getUpcomingEpisodes(
        seriesId: String,
        nowMs: Long,
        daysBack: Int,
        daysAhead: Int,
        maxEpisodes: Int,
        preferredAddonId: String? = null,
    ): SeriesMetaEpisodes? {
        val metadata = getMetaDetails(type = "show", id = seriesId, preferredAddonId = preferredAddonId) ?: return null
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
            artworkUrl = metadata.artworkUrl,
            addonId = metadata.addonId,
            episodes = episodes,
        )
    }

    private suspend fun getMetaDetails(
        type: String,
        id: String,
        preferredAddonId: String?,
    ): MetaDetails? {
        val normalizedId = parseLookupId(id).let { parsed -> buildLookupId(parsed.baseId, parsed.season, parsed.episode) }
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

        val addonId = nonBlank(manifest.optStringOrEmpty("id")) ?: seed.addonIdHint

        val resources = manifest.optJsonArray("resources") ?: JsonArray(emptyList())
        var declaredTypes = emptyList<String>()

        for (element in resources) {
            when (val resource = element) {
                // **`is String` is not a type a JSON string has, and this arm is
                // the reason the port needed the guard rather than a rename.**
                // A JSON *number* is a `JsonPrimitive` too, so widening the arm
                // would admit values `org.json` dropped, and `contentOrNull`
                // answers the same text for `1234` and `"1234"` -- `isString` is
                // the only discriminator.
                is JsonPrimitive -> {
                    val text = resource.contentOrNull
                    if (!resource.isString || text == null) continue
                    if (!text.equals("meta", ignoreCase = true)) continue
                    declaredTypes = listOf("movie", "series")
                }

                is JsonObject -> {
                    if (!resource.optStringOrEmpty("name").equals("meta", ignoreCase = true)) continue
                    declaredTypes = parseStringArray(resource.optJsonArray("types"))
                    break
                }

                // `JsonElement` is sealed, where `org.json`'s `Any?` was not, so
                // the `when` is exhaustiveness-checked now. `else` reproduces the
                // old fall-through: a JSON array element matched neither arm and
                // was dropped without a word.
                else -> Unit
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
        )
    }

    private suspend fun fetchMetaFromEndpoint(
        endpoint: AddonMetaEndpoint,
        type: String,
        id: String,
    ): MetaDetails? {
        val lookupId = endpoint.formatLookupId(id) ?: return null
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
        return parseMetaDetails(response.optJsonObject("meta"), endpoint.addonId)
    }

    private suspend fun fetchMetaFromCinemeta(type: String, id: String): MetaDetails? {
        val encodedId = URLEncoder.encode(id, StandardCharsets.UTF_8.name())
        for (baseUrl in CINEMETA_BASE_URLS) {
            val url = "${baseUrl.trimEnd('/')}/meta/${type.lowercase()}/$encodedId.json"
            val response = httpClient.getJsonObject(url) ?: continue
            parseMetaDetails(response.optJsonObject("meta"), CINEMETA_ADDON_ID)?.let { return it }
        }
        return null
    }

    private fun parseMetaDetails(meta: JsonObject?, addonId: String): MetaDetails? {
        if (meta == null) return null
        val name = nonBlank(meta.optStringOrEmpty("name")) ?: return null
        val videosArray = meta.optJsonArray("videos") ?: JsonArray(emptyList())
        val videos = buildList {
            for (element in videosArray) {
                val video = element as? JsonObject ?: continue
                val season = (video.optIntOrNull("season") ?: 0).takeIf { it >= 0 } ?: 0
                val episode = (video.optIntOrNull("episode") ?: 0).takeIf { it >= 0 } ?: 0
                add(
                    MetaVideo(
                        id = nonBlank(video.optStringOrEmpty("id")) ?: "$season:$episode",
                        title = nonBlank(video.optStringOrEmpty("title")),
                        season = season,
                        episode = episode,
                        released = nonBlank(video.optStringOrEmpty("released")),
                        overview = nonBlank(video.optStringOrEmpty("overview")),
                        thumbnailUrl = firstNonBlank(
                            video.optStringOrEmpty("thumbnail"),
                            video.optStringOrEmpty("thumbnailUrl"),
                        ),
                    )
                )
            }
        }
        return MetaDetails(
            seriesName = name,
            artworkUrl = firstNonBlank(meta.optStringOrEmpty("poster"), meta.optStringOrEmpty("background")),
            addonId = addonId,
            videos = videos,
        )
    }

    private fun parseStringArray(array: JsonArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (element in array) {
                val text = (element as? JsonPrimitive)?.contentOrNull ?: continue
                nonBlank(text)?.let(::add)
            }
        }
    }

    private fun parseJsonObject(raw: String?): JsonObject? {
        if (raw.isNullOrBlank()) return null
        // `Json.parseToJsonElement` raises `SerializationException` where
        // `JSONObject(raw)` raised `JSONException`; the `runCatching` absorbs
        // either, and the `:app` census measured **zero** `JSONException`
        // occurrences, so nothing could have depended on the type.
        return runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
    }

    private fun fallbackManifest(seed: AddonManifestSeed): JsonObject? {
        if (
            seed.addonIdHint.contains("cinemeta", ignoreCase = true) ||
                seed.manifestUrl.contains("cinemeta", ignoreCase = true)
        ) {
            return buildJsonObject {
                put("id", CINEMETA_ADDON_ID)
                put(
                    "resources",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("name", "meta")
                                put("types", buildJsonArray { add(JsonPrimitive("movie")); add(JsonPrimitive("series")) })
                            }
                        )
                    }
                )
            }
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

    private fun buildLookupId(contentId: String, season: Int?, episode: Int?): String {
        return if (season != null && season > 0 && episode != null && episode > 0) {
            "$contentId:$season:$episode"
        } else {
            contentId
        }
    }

    private companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
        private const val CINEMETA_ADDON_ID = "com.linvo.cinemeta"
        private val CINEMETA_BASE_URLS = listOf(
            "https://v3-cinemeta.strem.io",
            "http://v3-cinemeta.strem.io",
        )
    }
}

internal data class AddonMetaEndpoint(
    val addonId: String,
    val baseUrl: String,
    val encodedQuery: String?,
    val declaredTypes: List<String>,
) {
    fun formatLookupId(id: String): String? {
        val trimmed = id.trim()
        return trimmed.takeIf { it.isNotBlank() }
    }

    fun resolveType(type: String): String {
        val normalizedType = type.lowercase().trim()
        if (declaredTypes.contains(normalizedType)) {
            return normalizedType
        }
        return declaredTypes.firstOrNull { it.equals("series", ignoreCase = true) }?.lowercase() ?: "movie"
    }
}

internal data class MetaDetails(
    val seriesName: String,
    val artworkUrl: String?,
    val addonId: String?,
    val videos: List<MetaVideo>,
)

internal data class SeriesMetaEpisodes(
    val seriesName: String,
    val artworkUrl: String?,
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

private suspend fun CrispyHttpClient.getJsonObject(url: String): JsonObject? {
    val response = runCatching { get(url = url) }.getOrNull() ?: return null
    if (response.code !in 200..299) return null
    return runCatching { Json.parseToJsonElement(response.body).jsonObject }.getOrNull()
}

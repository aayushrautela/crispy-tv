package com.crispy.tv.metadata

import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.player.MetadataLabMediaType
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolved TMDB identity for a given IMDb ID.
 */
data class TmdbIdResult(
    val tmdbId: Int,
    val mediaType: MetadataLabMediaType,
)

class TmdbImdbIdResolver(
    private val apiKey: String,
    private val httpClient: CrispyHttpClient
) {
    /** Forward cache: "movie:12345" or "series:12345" -> IMDb ID (or NO_VALUE). */
    private val cache = ConcurrentHashMap<String, String>()

    /** Reverse cache: "tt1234567" -> TmdbIdResult (or null stored as explicit key). */
    private val reverseCache = ConcurrentHashMap<String, TmdbIdResult>()

    /** Tracks keys we've already looked up in reverseCache so null results don't re-fetch. */
    private val reverseLookedUp = ConcurrentHashMap<String, Boolean>()

    // ── Forward: contentId/tmdbId → IMDb ──────────────────────────────────

    suspend fun resolveImdbId(
        contentId: String,
        mediaType: MetadataLabMediaType
    ): String? {
        val normalized = contentId.trim()
        if (normalized.isEmpty()) return null

        if (normalized.startsWith("tt", ignoreCase = true)) {
            return normalized.lowercase(Locale.US)
        }

        if (apiKey.isBlank()) return null

        val tmdbId = tmdbIdFromContentId(normalized) ?: return null
        val key = "${mediaType.name.lowercase(Locale.US)}:$tmdbId"
        val cached = cache[key]
        if (cached != null) {
            return cached.takeUnless { it == NO_VALUE }
        }

        val imdb =
            when (mediaType) {
                MetadataLabMediaType.MOVIE -> fetchMovieImdbId(tmdbId)
                MetadataLabMediaType.SERIES -> fetchTvImdbId(tmdbId)
            }
        cache[key] = imdb ?: NO_VALUE
        return imdb
    }

    // ── Reverse: IMDb → TMDB (with media type detection) ─────────────────

    /**
     * Given an IMDb ID (e.g. "tt1234567"), resolve the corresponding TMDB ID
     * and media type. Uses TMDB `/find/{imdbId}?external_source=imdb_id`,
     * checking TV results first then movies (matching Nuvio behavior).
     */
    suspend fun resolveTmdbFromImdb(imdbId: String): TmdbIdResult? {
        val normalized = imdbId.trim().lowercase(Locale.US)
        if (normalized.isEmpty() || !normalized.startsWith("tt")) return null
        if (apiKey.isBlank()) return null

        if (reverseLookedUp.containsKey(normalized)) {
            return reverseCache[normalized]
        }

        val result = fetchTmdbByExternalId(normalized)
        if (result != null) {
            reverseCache[normalized] = result
            // Also populate forward cache for future resolveImdbId calls
            val forwardKey = "${result.mediaType.name.lowercase(Locale.US)}:${result.tmdbId}"
            cache.putIfAbsent(forwardKey, normalized)
        }
        reverseLookedUp[normalized] = true
        return result
    }

    // ── Internals ────────────────────────────────────────────────────────

    private suspend fun fetchMovieImdbId(tmdbId: Int): String? {
        val json = getJsonObject("movie/$tmdbId") ?: return null
        return normalizeImdbId(json.optString("imdb_id"))
    }

    private suspend fun fetchTvImdbId(tmdbId: Int): String? {
        val json = getJsonObject("tv/$tmdbId/external_ids") ?: return null
        return normalizeImdbId(json.optString("imdb_id"))
    }

    /**
     * TMDB /find endpoint: returns TV and movie results for an external ID.
     * We check TV first (like Nuvio) since series are more likely to have
     * ID resolution issues.
     */
    private suspend fun fetchTmdbByExternalId(imdbId: String): TmdbIdResult? {
        val json = getJsonObject("find/$imdbId", extraParams = "external_source=imdb_id")
            ?: return null

        // TV results first (matches Nuvio: TV → movie fallback)
        val tvResults = json.optJSONArray("tv_results")
        if (tvResults != null && tvResults.length() > 0) {
            val first = tvResults.optJSONObject(0)
            val id = first?.optInt("id", 0) ?: 0
            if (id > 0) {
                return TmdbIdResult(tmdbId = id, mediaType = MetadataLabMediaType.SERIES)
            }
        }

        // Movie fallback
        val movieResults = json.optJSONArray("movie_results")
        if (movieResults != null && movieResults.length() > 0) {
            val first = movieResults.optJSONObject(0)
            val id = first?.optInt("id", 0) ?: 0
            if (id > 0) {
                return TmdbIdResult(tmdbId = id, mediaType = MetadataLabMediaType.MOVIE)
            }
        }

        return null
    }

    private suspend fun getJsonObject(path: String, extraParams: String? = null): JSONObject? {
        val cleanPath = path.trimStart('/')
        val queryParts = buildList {
            add("api_key=${apiKey.trim()}")
            if (!extraParams.isNullOrBlank()) add(extraParams)
        }
        val url = "https://api.themoviedb.org/3/$cleanPath?${queryParts.joinToString("&")}"
            .toHttpUrl()

        val response =
            runCatching {
                httpClient.get(
                    url = url,
                    headers = Headers.headersOf("Accept", "application/json"),
                    callTimeoutMs = 12_000L
                )
            }.getOrNull() ?: return null

        if (response.code !in 200..299) return null
        val body = response.body
        if (body.isBlank()) return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    private fun tmdbIdFromContentId(contentId: String): Int? {
        return TMDB_ID_REGEX.find(contentId)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
    }

    private fun normalizeImdbId(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val normalized = trimmed.lowercase(Locale.US)
        return normalized.takeIf { it.startsWith("tt") && it.length >= 4 }
    }

    private companion object {
        private const val NO_VALUE = "__none__"
        private val TMDB_ID_REGEX = Regex("""\btmdb:(?:movie:|show:|tv:)?(\d+)""", RegexOption.IGNORE_CASE)
    }
}

package com.crispy.rewrite.metadata

import com.crispy.rewrite.network.CrispyHttpClient
import com.crispy.rewrite.player.MetadataLabMediaType
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class TmdbImdbIdResolver(
    private val apiKey: String,
    private val httpClient: CrispyHttpClient
) {
    private val cache = ConcurrentHashMap<String, String>()

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

    private suspend fun fetchMovieImdbId(tmdbId: Int): String? {
        val json = getJsonObject("movie/$tmdbId") ?: return null
        return normalizeImdbId(json.optString("imdb_id"))
    }

    private suspend fun fetchTvImdbId(tmdbId: Int): String? {
        val json = getJsonObject("tv/$tmdbId/external_ids") ?: return null
        return normalizeImdbId(json.optString("imdb_id"))
    }

    private suspend fun getJsonObject(path: String): JSONObject? {
        val url =
            "https://api.themoviedb.org/3/${path.trimStart('/')}?api_key=${apiKey.trim()}"
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
        private val TMDB_ID_REGEX = Regex("""\\btmdb:(?:movie:|show:|tv:)?(\\d+)""", RegexOption.IGNORE_CASE)
    }
}

package com.crispy.rewrite.metadata.tmdb

import com.crispy.rewrite.network.CrispyHttpClient
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

internal class TmdbJsonClient(
    private val apiKey: String,
    private val httpClient: CrispyHttpClient,
    private val callTimeoutMs: Long = 12_000L,
    private val cacheTtlMs: Long = 6 * 60 * 60 * 1000L
) {
    private data class CacheEntry(
        val timestampMs: Long,
        val json: JSONObject
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    suspend fun getJson(
        path: String,
        query: Map<String, String?> = emptyMap()
    ): JSONObject? {
        if (apiKey.isBlank()) return null
        val safePath = path.trim().trimStart('/')

        val cacheKey = buildCacheKey(safePath, query)
        val cached = getCached(cacheKey)
        if (cached != null) return cached

        val url = buildUrl(safePath, query)
        val json = httpClient.getJsonObject(url, callTimeoutMs) ?: return null
        cache[cacheKey] = CacheEntry(timestampMs = System.currentTimeMillis(), json = json)
        return JSONObject(json.toString())
    }

    private fun buildUrl(path: String, query: Map<String, String?>): HttpUrl {
        val builder = "${TmdbApi.BASE_URL}/$path".toHttpUrl().newBuilder()
        builder.addQueryParameter("api_key", apiKey)
        query.entries
            .sortedBy { it.key }
            .forEach { (key, value) ->
                val trimmed = value?.trim().orEmpty()
                if (trimmed.isNotBlank()) {
                    builder.addQueryParameter(key, trimmed)
                }
            }
        return builder.build()
    }

    private fun buildCacheKey(path: String, query: Map<String, String?>): String {
        val normalizedQuery =
            query.entries
                .sortedBy { it.key }
                .joinToString("&") { (k, v) ->
                    val value = v?.trim().orEmpty()
                    "$k=$value"
                }
        return if (normalizedQuery.isBlank()) path else "$path?$normalizedQuery"
    }

    private fun getCached(key: String): JSONObject? {
        val entry = cache[key] ?: return null
        val age = System.currentTimeMillis() - entry.timestampMs
        if (age > cacheTtlMs) {
            cache.remove(key)
            return null
        }
        return JSONObject(entry.json.toString())
    }
}

private suspend fun CrispyHttpClient.getJsonObject(
    url: HttpUrl,
    callTimeoutMs: Long
): JSONObject? {
    val response =
        runCatching {
            get(
                url = url,
                headers = Headers.Builder().add("Accept", "application/json").build(),
                callTimeoutMs = callTimeoutMs
            )
        }.getOrNull() ?: return null

    if (response.code !in 200..299) return null
    val body = response.body.trim()
    if (body.isBlank()) return null
    return runCatching { JSONObject(body) }.getOrNull()
}

package com.crispy.tv.home

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal class RecommendationCatalogDiskCacheStore(appContext: Context) {
    private val cacheDirectory = appContext.filesDir.resolve(CACHE_DIRECTORY_NAME).also { directory ->
        if (!directory.exists()) {
            directory.mkdirs()
        }
    }

    data class CachedPayload(
        val payload: String,
        val timestampMs: Long,
    ) {
        fun ageMs(nowMs: Long = System.currentTimeMillis()): Long {
            return (nowMs - timestampMs).coerceAtLeast(0L)
        }
    }

    suspend fun read(cacheKey: String, maxAgeMs: Long? = null): CachedPayload? = withContext(Dispatchers.IO) {
        val file = cacheFile(cacheKey)
        val raw = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull() ?: return@withContext null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext null
        val payload = json.optString("payload").trim()
        val timestampMs = json.optLong("timestamp_ms", 0L)
        if (payload.isBlank() || timestampMs <= 0L) {
            return@withContext null
        }
        val entry = CachedPayload(payload = payload, timestampMs = timestampMs)
        val limit = maxAgeMs?.takeIf { it > 0L }
        return@withContext if (limit != null && entry.ageMs() > limit) null else entry
    }

    suspend fun write(cacheKey: String, payload: String, timestampMs: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val normalizedPayload = payload.trim()
        if (normalizedPayload.isEmpty()) {
            return@withContext
        }
        val file = cacheFile(cacheKey)
        val json =
            JSONObject()
                .put("timestamp_ms", timestampMs)
                .put("payload", normalizedPayload)
                .toString()
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json, StandardCharsets.UTF_8)
        }
    }

    private fun cacheFile(cacheKey: String): File {
        return cacheDirectory.resolve("${cacheKey.sha256()}.json")
    }

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(StandardCharsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                append(((byte.toInt() ushr 4) and 0x0F).toString(16))
                append((byte.toInt() and 0x0F).toString(16))
            }
        }
    }

    private companion object {
        private const val CACHE_DIRECTORY_NAME = "recommendation_catalog_cache"
    }
}

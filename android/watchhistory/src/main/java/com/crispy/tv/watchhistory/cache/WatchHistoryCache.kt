package com.crispy.tv.watchhistory.cache

import android.content.Context
import android.util.Log
import com.crispy.tv.player.ContinueWatchingEntry
import com.crispy.tv.player.ContinueWatchingResult
import com.crispy.tv.player.WatchProvider
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class WatchHistoryCache(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val providerCacheDir: File = File(appContext.filesDir, "watch_provider_cache").also { it.mkdirs() }

    suspend fun getCachedContinueWatching(
        limit: Int,
        nowMs: Long,
        source: WatchProvider?,
        localFallback: suspend () -> List<ContinueWatchingEntry>,
        normalize: (List<ContinueWatchingEntry>, Long, Int) -> List<ContinueWatchingEntry>,
    ): ContinueWatchingResult {
        val targetLimit = limit.coerceAtLeast(1)

        if (source == WatchProvider.LOCAL) {
            val local = localFallback().take(targetLimit)
            val status = if (local.isNotEmpty()) "" else "No continue watching entries yet."
            return ContinueWatchingResult(statusMessage = status, entries = local)
        }

        val providers =
            when (source) {
                WatchProvider.TRAKT -> listOf(WatchProvider.TRAKT)
                WatchProvider.SIMKL -> listOf(WatchProvider.SIMKL)
                null -> listOf(WatchProvider.TRAKT, WatchProvider.SIMKL)
            }

        val mergedEntries = mutableListOf<ContinueWatchingEntry>()
        for (provider in providers) {
            val cached = readContinueWatchingCache(provider) ?: continue
            mergedEntries += cached.value.entries
        }

        if (mergedEntries.isEmpty()) {
            val label =
                when (source) {
                    WatchProvider.TRAKT -> "Trakt"
                    WatchProvider.SIMKL -> "Simkl"
                    else -> "provider"
                }
            return ContinueWatchingResult(statusMessage = "No cached $label continue watching entries.")
        }

        val normalized = normalize(mergedEntries, nowMs, targetLimit)
        return ContinueWatchingResult(statusMessage = "", entries = normalized)
    }

    suspend fun writeContinueWatchingCache(provider: WatchProvider, result: ContinueWatchingResult) {
        if (provider == WatchProvider.LOCAL) return

        val updatedAt = System.currentTimeMillis()
        val entriesJson = JSONArray()
        for (entry in result.entries) {
            entriesJson.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("mediaKey", entry.mediaKey)
                    .put("localKey", entry.localKey)
                    .put("provider", entry.provider)
                    .put("providerId", entry.providerId)
                    .put("mediaType", entry.mediaType)
                    .put("title", entry.title)
                    .put("season", entry.season)
                    .put("episode", entry.episode)
                    .put("progressPercent", entry.progressPercent)
                    .put("lastUpdatedEpochMs", entry.lastUpdatedEpochMs)
                    .put("source", entry.source.name)
                    .put("isUpNextPlaceholder", entry.isUpNextPlaceholder)
                    .put("posterUrl", entry.posterUrl)
                    .put("backdropUrl", entry.backdropUrl)
                    .put("logoUrl", entry.logoUrl)
                    .put("addonId", entry.addonId)
                    .put("subtitle", entry.subtitle)
                    .put("dismissible", entry.dismissible)
                    .put("absoluteEpisodeNumber", entry.absoluteEpisodeNumber)
            )
        }

        val json =
            JSONObject()
                .put("updatedAtEpochMs", updatedAt)
                .put("statusMessage", result.statusMessage)
                .put("isError", result.isError)
                .put("entries", entriesJson)

        writeFileAtomic(continueWatchingCacheFile(provider), json.toString())
    }

    fun clearProviderCaches(provider: WatchProvider) {
        if (provider == WatchProvider.LOCAL) return
        runCatching { continueWatchingCacheFile(provider).delete() }
    }

    private data class CachedSnapshot<T>(
        val updatedAtEpochMs: Long,
        val value: T,
    )

    private fun continueWatchingCacheFile(provider: WatchProvider): File {
        val name = provider.name.lowercase(Locale.US)
        return File(providerCacheDir, "${name}_continue_watching.json")
    }

    private suspend fun writeFileAtomic(file: File, text: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) {
                    file.writeText(text)
                    runCatching { tmp.delete() }
                }
            }.onFailure { error ->
                Log.w(TAG, "Failed to write cache file: ${file.absolutePath}", error)
            }
        }
    }

    private suspend fun readContinueWatchingCache(provider: WatchProvider): CachedSnapshot<ContinueWatchingResult>? {
        if (provider == WatchProvider.LOCAL) return null

        val file = continueWatchingCacheFile(provider)
        val text =
            withContext(Dispatchers.IO) {
                if (!file.exists()) return@withContext null
                runCatching { file.readText() }.getOrNull()
            } ?: return null

        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val updatedAt = root.optLong("updatedAtEpochMs", -1L)
        if (updatedAt <= 0L) return null

        val entriesArray = root.optJSONArray("entries") ?: JSONArray()
        val entries = mutableListOf<ContinueWatchingEntry>()
        for (index in 0 until entriesArray.length()) {
            val obj = entriesArray.optJSONObject(index) ?: continue
            val id = obj.optString("id").trim()
            val runtimeProvider = obj.optString("provider").trim()
            val runtimeProviderId = obj.optString("providerId").trim()
            val mediaKey = obj.optString("mediaKey").trim().ifBlank { null }
            val localKey = obj.optString("localKey").trim().ifBlank { mediaKey ?: id }
            val mediaType = obj.optString("mediaType").trim()
            val title = obj.optString("title").trim()
            if (id.isBlank() || runtimeProvider.isBlank() || runtimeProviderId.isBlank() || mediaType.isBlank() || title.isBlank()) continue
            val season = obj.optInt("season", 0).takeIf { it > 0 }
            val episode = obj.optInt("episode", 0).takeIf { it > 0 }
            val progress = obj.optDouble("progressPercent", 0.0)
            val lastUpdated = obj.optLong("lastUpdatedEpochMs", updatedAt)
            val sourceValue = runCatching { WatchProvider.valueOf(obj.optString("source").trim()) }.getOrNull() ?: provider
            val isUpNext = obj.optBoolean("isUpNextPlaceholder", false)
            val posterUrl = obj.optString("posterUrl").trim().takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
            val backdropUrl = obj.optString("backdropUrl").trim().takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
            val logoUrl = obj.optString("logoUrl").trim().takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
            val addonId = obj.optString("addonId").trim().takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
            val subtitle = obj.optString("subtitle").trim().ifEmpty { null }
            val dismissible = obj.optBoolean("dismissible", false)
            val absoluteEpisodeNumber = obj.optInt("absoluteEpisodeNumber", 0).takeIf { it > 0 }

            entries.add(
                ContinueWatchingEntry(
                    id = id,
                    mediaKey = mediaKey,
                    localKey = localKey,
                    provider = runtimeProvider,
                    providerId = runtimeProviderId,
                    mediaType = mediaType,
                    title = title,
                    season = season,
                    episode = episode,
                    progressPercent = progress,
                    lastUpdatedEpochMs = lastUpdated,
                    source = sourceValue,
                    isUpNextPlaceholder = isUpNext,
                    posterUrl = posterUrl,
                    backdropUrl = backdropUrl,
                    logoUrl = logoUrl,
                    addonId = addonId,
                    subtitle = subtitle,
                    dismissible = dismissible,
                    absoluteEpisodeNumber = absoluteEpisodeNumber,
                )
            )
        }

        val statusMessage = root.optString("statusMessage").trim().ifEmpty { "Cached continue watching." }
        val isError = root.optBoolean("isError", false)
        return CachedSnapshot(
            updatedAtEpochMs = updatedAt,
            value = ContinueWatchingResult(statusMessage = statusMessage, entries = entries, isError = isError),
        )
    }

    private companion object {
        private const val TAG = "WatchHistoryCache"
    }
}

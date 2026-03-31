package com.crispy.tv.watchhistory.cache

import android.content.Context
import android.util.Log
import com.crispy.tv.player.ContinueWatchingEntry
import com.crispy.tv.player.ContinueWatchingResult
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.ProviderExternalIds
import com.crispy.tv.player.ProviderLibraryFolder
import com.crispy.tv.player.ProviderLibraryItem
import com.crispy.tv.player.ProviderLibrarySnapshot
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

    suspend fun getCachedProviderLibrary(
        limitPerFolder: Int,
        source: WatchProvider?,
    ): ProviderLibrarySnapshot {
        if (source == WatchProvider.LOCAL) {
            return ProviderLibrarySnapshot(statusMessage = "Local source selected. Provider library unavailable.")
        }

        fun applyLimit(snapshot: ProviderLibrarySnapshot): ProviderLibrarySnapshot {
            val targetLimit = limitPerFolder.coerceAtLeast(1)
            val byKey = snapshot.items.groupBy { "${it.provider.name}:${it.folderId}" }
            val limitedFolders = mutableListOf<ProviderLibraryFolder>()
            val limitedItems = mutableListOf<ProviderLibraryItem>()
            for (folder in snapshot.folders) {
                val key = "${folder.provider.name}:${folder.id}"
                val items = byKey[key].orEmpty().sortedByDescending { it.addedAtEpochMs }.take(targetLimit)
                limitedItems += items
                limitedFolders += folder.copy(itemCount = items.size)
            }
            return snapshot.copy(folders = limitedFolders, items = limitedItems)
        }

        val providers =
            when (source) {
                WatchProvider.TRAKT -> listOf(WatchProvider.TRAKT)
                WatchProvider.SIMKL -> listOf(WatchProvider.SIMKL)
                null -> listOf(WatchProvider.TRAKT, WatchProvider.SIMKL)
            }

        val mergedFolders = mutableListOf<ProviderLibraryFolder>()
        val mergedItems = mutableListOf<ProviderLibraryItem>()

        for (provider in providers) {
            val cached = readProviderLibraryCache(provider) ?: continue
            mergedFolders += cached.value.folders
            mergedItems += cached.value.items
        }

        if (mergedFolders.isEmpty() && mergedItems.isEmpty()) {
            val label =
                when (source) {
                    WatchProvider.TRAKT -> "Trakt"
                    WatchProvider.SIMKL -> "Simkl"
                    else -> "provider"
                }
            return ProviderLibrarySnapshot(statusMessage = "No cached $label library data available.")
        }

        val limited = applyLimit(
            ProviderLibrarySnapshot(
                statusMessage = "",
                folders = mergedFolders.sortedBy { it.label.lowercase(Locale.US) },
                items = mergedItems.sortedByDescending { it.addedAtEpochMs },
            )
        )

        return limited.copy(
            folders = limited.folders.sortedBy { it.label.lowercase(Locale.US) },
            items = limited.items.sortedByDescending { it.addedAtEpochMs },
        )
    }

    suspend fun writeContinueWatchingCache(provider: WatchProvider, result: ContinueWatchingResult) {
        if (provider == WatchProvider.LOCAL) return

        val updatedAt = System.currentTimeMillis()
        val entriesJson = JSONArray()
        for (entry in result.entries) {
            entriesJson.put(
                JSONObject()
                    .put("contentId", entry.contentId)
                    .put("contentType", entry.contentType.name)
                    .put("title", entry.title)
                    .put("season", entry.season)
                    .put("episode", entry.episode)
                    .put("progressPercent", entry.progressPercent)
                    .put("lastUpdatedEpochMs", entry.lastUpdatedEpochMs)
                    .put("provider", entry.provider.name)
                    .put("providerPlaybackId", entry.providerPlaybackId)
                    .put("isUpNextPlaceholder", entry.isUpNextPlaceholder)
                    .put("posterUrl", entry.posterUrl)
                    .put("backdropUrl", entry.backdropUrl)
                    .put("logoUrl", entry.logoUrl)
                    .put("addonId", entry.addonId)
                    .put("metadataProviderId", entry.metadataProviderId)
                    .put("metadataProvider", entry.metadataProvider)
                    .put("parentProvider", entry.parentProvider)
                    .put("parentProviderId", entry.parentProviderId)
                    .put("absoluteEpisodeNumber", entry.absoluteEpisodeNumber)
                    .put("playbackContentId", entry.playbackContentId)
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

    suspend fun writeProviderLibraryCache(provider: WatchProvider, snapshot: ProviderLibrarySnapshot) {
        if (provider == WatchProvider.LOCAL) return

        val updatedAt = System.currentTimeMillis()
        val foldersJson = JSONArray()
        for (folder in snapshot.folders) {
            foldersJson.put(
                JSONObject()
                    .put("id", folder.id)
                    .put("label", folder.label)
                    .put("provider", folder.provider.name)
                    .put("itemCount", folder.itemCount)
            )
        }

        val itemsJson = JSONArray()
        for (item in snapshot.items) {
            itemsJson.put(
                JSONObject()
                    .put("provider", item.provider.name)
                    .put("folderId", item.folderId)
                    .put("contentId", item.contentId)
                    .put("contentType", item.contentType.name)
                    .put("title", item.title)
                    .put("posterUrl", item.posterUrl)
                    .put("backdropUrl", item.backdropUrl)
                    .put(
                        "externalIds",
                        item.externalIds?.let { ids ->
                            JSONObject()
                                .put("tmdb", ids.tmdb)
                                .put("imdb", ids.imdb)
                                .put("tvdb", ids.tvdb)
                        },
                    )
                    .put("season", item.season)
                    .put("episode", item.episode)
                    .put("addedAtEpochMs", item.addedAtEpochMs)
            )
        }

        val json =
            JSONObject()
                .put("updatedAtEpochMs", updatedAt)
                .put("statusMessage", snapshot.statusMessage)
                .put("folders", foldersJson)
                .put("items", itemsJson)

        writeFileAtomic(providerLibraryCacheFile(provider), json.toString())
    }

    fun clearProviderCaches(provider: WatchProvider) {
        if (provider == WatchProvider.LOCAL) return
        runCatching { continueWatchingCacheFile(provider).delete() }
        runCatching { providerLibraryCacheFile(provider).delete() }
    }

    suspend fun invalidateProviderLibraryCache(provider: WatchProvider) {
        if (provider == WatchProvider.LOCAL) return
        withContext(Dispatchers.IO) {
            runCatching { providerLibraryCacheFile(provider).delete() }
        }
    }

    private data class CachedSnapshot<T>(
        val updatedAtEpochMs: Long,
        val value: T,
    )

    private fun continueWatchingCacheFile(provider: WatchProvider): File {
        val name = provider.name.lowercase(Locale.US)
        return File(providerCacheDir, "${name}_continue_watching.json")
    }

    private fun providerLibraryCacheFile(provider: WatchProvider): File {
        val name = provider.name.lowercase(Locale.US)
        return File(providerCacheDir, "${name}_library.json")
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
            val contentId = obj.optString("contentId").trim()
            if (contentId.isBlank()) continue
            val contentType = runCatching { MetadataLabMediaType.valueOf(obj.optString("contentType").trim()) }.getOrNull() ?: continue
            val title = obj.optString("title").trim().ifEmpty { contentId }
            val season = obj.optInt("season", 0).takeIf { it > 0 }
            val episode = obj.optInt("episode", 0).takeIf { it > 0 }
            val progress = obj.optDouble("progressPercent", 0.0)
            val lastUpdated = obj.optLong("lastUpdatedEpochMs", updatedAt)
            val providerValue = runCatching { WatchProvider.valueOf(obj.optString("provider").trim()) }.getOrNull() ?: provider
            val playbackId = obj.optString("providerPlaybackId").trim().ifEmpty { null }
            val isUpNext = obj.optBoolean("isUpNextPlaceholder", false)
            val posterUrl = obj.optString("posterUrl").trim().takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
            val backdropUrl = obj.optString("backdropUrl").trim().takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
            val logoUrl = obj.optString("logoUrl").trim().takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
            val addonId = obj.optString("addonId").trim().takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
            val metadataProviderId = obj.optString("metadataProviderId").trim().ifEmpty { null }
            val metadataProvider = obj.optString("metadataProvider").trim().ifEmpty { null }
            val parentProvider = obj.optString("parentProvider").trim().ifEmpty { null }
            val parentProviderId = obj.optString("parentProviderId").trim().ifEmpty { null }
            val absoluteEpisodeNumber = obj.optInt("absoluteEpisodeNumber", 0).takeIf { it > 0 }
            val playbackContentId = obj.optString("playbackContentId").trim().ifEmpty { contentId }

            entries.add(
                ContinueWatchingEntry(
                    contentId = contentId,
                    contentType = contentType,
                    title = title,
                    season = season,
                    episode = episode,
                    progressPercent = progress,
                    lastUpdatedEpochMs = lastUpdated,
                    provider = providerValue,
                    providerPlaybackId = playbackId,
                    isUpNextPlaceholder = isUpNext,
                    posterUrl = posterUrl,
                    backdropUrl = backdropUrl,
                    logoUrl = logoUrl,
                    addonId = addonId,
                    metadataProviderId = metadataProviderId,
                    metadataProvider = metadataProvider,
                    parentProvider = parentProvider,
                    parentProviderId = parentProviderId,
                    absoluteEpisodeNumber = absoluteEpisodeNumber,
                    playbackContentId = playbackContentId,
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

    private suspend fun readProviderLibraryCache(provider: WatchProvider): CachedSnapshot<ProviderLibrarySnapshot>? {
        if (provider == WatchProvider.LOCAL) return null

        val file = providerLibraryCacheFile(provider)
        val text =
            withContext(Dispatchers.IO) {
                if (!file.exists()) return@withContext null
                runCatching { file.readText() }.getOrNull()
            } ?: return null

        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val updatedAt = root.optLong("updatedAtEpochMs", -1L)
        if (updatedAt <= 0L) return null

        val foldersArray = root.optJSONArray("folders") ?: JSONArray()
        val folders = mutableListOf<ProviderLibraryFolder>()
        for (index in 0 until foldersArray.length()) {
            val obj = foldersArray.optJSONObject(index) ?: continue
            val id = obj.optString("id").trim()
            val label = obj.optString("label").trim()
            if (id.isBlank() || label.isBlank()) continue
            val providerValue = runCatching { WatchProvider.valueOf(obj.optString("provider").trim()) }.getOrNull() ?: provider
            val itemCount = obj.optInt("itemCount", 0).coerceAtLeast(0)
            folders.add(
                ProviderLibraryFolder(
                    id = id,
                    label = label,
                    provider = providerValue,
                    itemCount = itemCount,
                )
            )
        }

        val itemsArray = root.optJSONArray("items") ?: JSONArray()
        val items = mutableListOf<ProviderLibraryItem>()
        for (index in 0 until itemsArray.length()) {
            val obj = itemsArray.optJSONObject(index) ?: continue
            val folderId = obj.optString("folderId").trim()
            val contentId = obj.optString("contentId").trim()
            if (folderId.isBlank() || contentId.isBlank()) continue
            val contentType = runCatching { MetadataLabMediaType.valueOf(obj.optString("contentType").trim()) }.getOrNull() ?: continue
            val title = obj.optString("title").trim().ifEmpty { contentId }
            val posterUrl = obj.optString("posterUrl").trim().takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
            val backdropUrl = obj.optString("backdropUrl").trim().takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
            val externalIds = obj.optProviderExternalIds()
            val season = obj.optInt("season", 0).takeIf { it > 0 }
            val episode = obj.optInt("episode", 0).takeIf { it > 0 }
            val addedAtEpochMs = obj.optLong("addedAtEpochMs", updatedAt)
            val providerValue = runCatching { WatchProvider.valueOf(obj.optString("provider").trim()) }.getOrNull() ?: provider

            items.add(
                ProviderLibraryItem(
                    provider = providerValue,
                    folderId = folderId,
                    contentId = contentId,
                    contentType = contentType,
                    title = title,
                    posterUrl = posterUrl,
                    backdropUrl = backdropUrl,
                    externalIds = externalIds,
                    season = season,
                    episode = episode,
                    addedAtEpochMs = addedAtEpochMs,
                )
            )
        }

        val statusMessage = root.optString("statusMessage").trim().ifEmpty { "Cached provider library." }
        return CachedSnapshot(
            updatedAtEpochMs = updatedAt,
            value = ProviderLibrarySnapshot(statusMessage = statusMessage, folders = folders, items = items),
        )
    }

    private companion object {
        private const val TAG = "WatchHistoryCache"
    }
}

private fun JSONObject.optProviderExternalIds(): ProviderExternalIds? {
    val json = optJSONObject("externalIds") ?: return null
    val tmdb = json.optInt("tmdb").takeIf { it > 0 }
    val imdb = json.optString("imdb").trim().ifBlank { null }
    val tvdb = json.optInt("tvdb").takeIf { it > 0 }
    if (tmdb == null && imdb == null && tvdb == null) {
        return null
    }
    return ProviderExternalIds(tmdb = tmdb, imdb = imdb, tvdb = tvdb)
}

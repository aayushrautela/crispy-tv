package com.crispy.tv.watchhistory.local

import android.content.SharedPreferences
import com.crispy.tv.domain.metadata.normalizeNuvioMediaId
import com.crispy.tv.player.ContinueWatchingEntry
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.WatchHistoryEntry
import com.crispy.tv.player.WatchHistoryRequest
import com.crispy.tv.player.WatchHistoryResult
import com.crispy.tv.player.WatchProvider
import com.crispy.tv.player.WatchProviderAuthState
import com.crispy.tv.watchhistory.KEY_LOCAL_WATCHED_ITEMS
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal class LocalWatchHistoryStore(
    private val prefs: SharedPreferences,
) {
    fun listLocalHistory(limit: Int, authState: WatchProviderAuthState): WatchHistoryResult {
        val entries =
            loadEntries()
                .sortedByDescending { item -> item.watchedAtEpochMs }
                .take(limit.coerceAtLeast(1))
                .map { item -> item.toPublicEntry() }

        val status =
            if (entries.isEmpty()) {
                "No local watched entries yet."
            } else {
                "Loaded ${entries.size} local watched entries."
            }

        return WatchHistoryResult(
            statusMessage = status,
            entries = entries,
            authState = authState,
        )
    }

    fun exportLocalHistory(): List<WatchHistoryEntry> {
        return loadEntries()
            .sortedByDescending { item -> item.watchedAtEpochMs }
            .map { item -> item.toPublicEntry() }
    }

    fun replaceLocalHistory(entries: List<WatchHistoryEntry>, authState: WatchProviderAuthState): WatchHistoryResult {
        if (entries.isEmpty()) {
            val current = loadEntries().sortedByDescending { item -> item.watchedAtEpochMs }.map { it.toPublicEntry() }
            return WatchHistoryResult(
                statusMessage = "Remote watched history empty. Kept local history unchanged.",
                entries = current,
                authState = authState,
            )
        }

        val normalized =
            entries.mapNotNull { entry ->
                val contentId = normalizeNuvioMediaId(entry.contentId).contentId.trim()
                if (contentId.isEmpty()) {
                    return@mapNotNull null
                }

                val season = entry.season?.takeIf { value -> value > 0 }
                val episode = entry.episode?.takeIf { value -> value > 0 }
                if (entry.contentType == MetadataLabMediaType.SERIES && (season == null || episode == null)) {
                    return@mapNotNull null
                }

                LocalWatchedItem(
                    contentId = contentId,
                    contentType = entry.contentType,
                    title = entry.title.trim().ifEmpty { contentId },
                    season = season,
                    episode = episode,
                    watchedAtEpochMs = entry.watchedAtEpochMs.takeIf { value -> value > 0 } ?: System.currentTimeMillis(),
                )
            }

        val merged = dedupeEntries(normalized)
        if (merged.isNotEmpty()) {
            saveEntries(merged)
        }

        return WatchHistoryResult(
            statusMessage = "Reconciled ${merged.size} remote watched entries to local history.",
            entries = merged.sortedByDescending { item -> item.watchedAtEpochMs }.map { item -> item.toPublicEntry() },
            authState = authState,
        )
    }

    fun normalizeRequest(request: WatchHistoryRequest): NormalizedWatchRequest {
        val normalizedId = normalizeNuvioMediaId(request.contentId)
        val contentId = normalizedId.contentId.trim()
        require(contentId.isNotEmpty()) { "Content ID is required" }

        val isSeries = request.contentType == MetadataLabMediaType.SERIES
        val season = if (isSeries) request.season ?: normalizedId.season else null
        val episode = if (isSeries) request.episode ?: normalizedId.episode else null
        if (isSeries) {
            require(season != null && season > 0) { "Season must be a positive number" }
            require(episode != null && episode > 0) { "Episode must be a positive number" }
        }

        val requestRemoteImdbId = request.remoteImdbId?.trim()
        val remoteImdbId =
            when {
                contentId.startsWith("tt", ignoreCase = true) -> contentId.lowercase()
                requestRemoteImdbId?.startsWith("tt", ignoreCase = true) == true -> requestRemoteImdbId.lowercase()
                else -> null
            }

        val title =
            request.title
                ?.trim()
                ?.takeIf { value -> value.isNotEmpty() }
                ?: if (isSeries) {
                    "$contentId S$season E$episode"
                } else {
                    contentId
                }

        return NormalizedWatchRequest(
            contentId = contentId,
            contentType = request.contentType,
            title = title,
            season = season,
            episode = episode,
            watchedAtEpochMs = System.currentTimeMillis(),
            remoteImdbId = remoteImdbId,
        )
    }

    fun loadEntries(): List<LocalWatchedItem> {
        val raw = prefs.getString(KEY_LOCAL_WATCHED_ITEMS, null) ?: return emptyList()
        return runCatching {
            val values = mutableListOf<LocalWatchedItem>()
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                LocalWatchedItem.fromJson(obj)?.let(values::add)
            }
            values
        }.getOrElse {
            emptyList()
        }
    }

    fun saveEntries(items: List<LocalWatchedItem>) {
        val array = JSONArray()
        items.forEach { item -> array.put(item.toJson()) }
        prefs.edit().putString(KEY_LOCAL_WATCHED_ITEMS, array.toString()).apply()
    }

    fun upsertEntry(existing: List<LocalWatchedItem>, next: LocalWatchedItem): List<LocalWatchedItem> {
        val byKey = linkedMapOf<String, LocalWatchedItem>()
        existing.forEach { item ->
            byKey[watchedKey(item.contentType, item.contentId, item.season, item.episode)] = item
        }
        val key = watchedKey(next.contentType, next.contentId, next.season, next.episode)
        val current = byKey[key]
        if (current == null || next.watchedAtEpochMs >= current.watchedAtEpochMs) {
            byKey[key] = next
        }
        return byKey.values.toList()
    }

    fun dedupeEntries(items: List<LocalWatchedItem>): List<LocalWatchedItem> {
        val byKey = linkedMapOf<String, LocalWatchedItem>()
        items.forEach { item ->
            val key = watchedKey(item.contentType, item.contentId, item.season, item.episode)
            val current = byKey[key]
            if (current == null || item.watchedAtEpochMs >= current.watchedAtEpochMs) {
                byKey[key] = item
            }
        }
        return byKey.values.toList()
    }

    fun removeEntry(existing: List<LocalWatchedItem>, request: NormalizedWatchRequest): List<LocalWatchedItem> {
        val removalKey = watchedKey(request.contentType, request.contentId, request.season, request.episode)
        return existing.filterNot { item ->
            watchedKey(item.contentType, item.contentId, item.season, item.episode) == removalKey
        }
    }

    fun localContinueWatchingFallback(): List<ContinueWatchingEntry> {
        return loadEntries()
            .sortedByDescending { it.watchedAtEpochMs }
            .map { item ->
                ContinueWatchingEntry(
                    contentId = item.contentId,
                    contentType = item.contentType,
                    title = item.title,
                    season = item.season,
                    episode = item.episode,
                    progressPercent = 100.0,
                    lastUpdatedEpochMs = item.watchedAtEpochMs,
                    provider = WatchProvider.LOCAL,
                )
            }
    }

    private fun watchedKey(contentType: MetadataLabMediaType, contentId: String, season: Int?, episode: Int?): String {
        val normalizedId = normalizeNuvioMediaId(contentId).contentId.trim().lowercase(Locale.US)
        val typeKey = contentType.name.lowercase(Locale.US)
        return "$typeKey:$normalizedId::${season ?: -1}::${episode ?: -1}"
    }
}

internal data class NormalizedWatchRequest(
    val contentId: String,
    val contentType: MetadataLabMediaType,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val watchedAtEpochMs: Long,
    val remoteImdbId: String?,
) {
    fun toLocalWatchedItem(): LocalWatchedItem {
        return LocalWatchedItem(
            contentId = contentId,
            contentType = contentType,
            title = title,
            season = season,
            episode = episode,
            watchedAtEpochMs = watchedAtEpochMs,
        )
    }
}

internal data class LocalWatchedItem(
    val contentId: String,
    val contentType: MetadataLabMediaType,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val watchedAtEpochMs: Long,
) {
    fun toPublicEntry(): WatchHistoryEntry {
        return WatchHistoryEntry(
            contentId = contentId,
            contentType = contentType,
            title = title,
            season = season,
            episode = episode,
            watchedAtEpochMs = watchedAtEpochMs,
        )
    }

    fun toJson(): JSONObject {
        return JSONObject()
            .put("content_id", contentId)
            .put("content_type", contentType.name.lowercase())
            .put("title", title)
            .put("season", season)
            .put("episode", episode)
            .put("watched_at", watchedAtEpochMs)
    }

    companion object {
        fun fromJson(json: JSONObject): LocalWatchedItem? {
            val contentId = json.optString("content_id").trim()
            if (contentId.isEmpty()) {
                return null
            }

            val contentType =
                when (json.optString("content_type").trim().lowercase()) {
                    "movie" -> MetadataLabMediaType.MOVIE
                    "series" -> MetadataLabMediaType.SERIES
                    else -> return null
                }
            val title = json.optString("title").trim().ifEmpty { contentId }
            val season = json.optInt("season", Int.MIN_VALUE).takeIf { value -> value > 0 }
            val episode = json.optInt("episode", Int.MIN_VALUE).takeIf { value -> value > 0 }
            val watchedAt = json.optLong("watched_at", System.currentTimeMillis())

            return LocalWatchedItem(
                contentId = contentId,
                contentType = contentType,
                title = title,
                season = season,
                episode = episode,
                watchedAtEpochMs = watchedAt,
            )
        }
    }
}

package com.crispy.rewrite.metadata

import android.content.Context
import com.crispy.rewrite.domain.metadata.normalizeNuvioMediaId
import com.crispy.rewrite.player.MetadataLabMediaType
import com.crispy.rewrite.player.WatchHistoryEntry
import com.crispy.rewrite.player.WatchHistoryLabResult
import com.crispy.rewrite.player.WatchHistoryLabService
import com.crispy.rewrite.player.WatchHistoryRequest
import com.crispy.rewrite.player.WatchProviderAuthState
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

class RemoteWatchHistoryLabService(
    context: Context,
    private val traktClientId: String,
    private val simklClientId: String
) : WatchHistoryLabService {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun updateAuthTokens(traktAccessToken: String, simklAccessToken: String) {
        val normalizedTrakt = traktAccessToken.trim()
        val normalizedSimkl = simklAccessToken.trim()

        prefs.edit()
            .apply {
                if (normalizedTrakt.isNotEmpty()) {
                    putString(KEY_TRAKT_TOKEN, normalizedTrakt)
                } else {
                    remove(KEY_TRAKT_TOKEN)
                }

                if (normalizedSimkl.isNotEmpty()) {
                    putString(KEY_SIMKL_TOKEN, normalizedSimkl)
                } else {
                    remove(KEY_SIMKL_TOKEN)
                }
            }
            .apply()
    }

    override fun authState(): WatchProviderAuthState {
        return WatchProviderAuthState(
            traktAuthenticated = traktAccessToken().isNotEmpty(),
            simklAuthenticated = simklAccessToken().isNotEmpty()
        )
    }

    override suspend fun listLocalHistory(limit: Int): WatchHistoryLabResult {
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

        return WatchHistoryLabResult(
            statusMessage = status,
            entries = entries,
            authState = authState()
        )
    }

    override suspend fun exportLocalHistory(): List<WatchHistoryEntry> {
        return loadEntries()
            .sortedByDescending { item -> item.watchedAtEpochMs }
            .map { item -> item.toPublicEntry() }
    }

    override suspend fun replaceLocalHistory(entries: List<WatchHistoryEntry>): WatchHistoryLabResult {
        if (entries.isEmpty()) {
            val current = loadEntries().sortedByDescending { item -> item.watchedAtEpochMs }.map { it.toPublicEntry() }
            return WatchHistoryLabResult(
                statusMessage = "Remote watched history empty. Kept local history unchanged.",
                entries = current,
                authState = authState()
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
                    watchedAtEpochMs = entry.watchedAtEpochMs.takeIf { value -> value > 0 } ?: System.currentTimeMillis()
                )
            }

        val merged = dedupeEntries(normalized)
        if (merged.isNotEmpty()) {
            saveEntries(merged)
        }

        return WatchHistoryLabResult(
            statusMessage = "Reconciled ${merged.size} remote watched entries to local history.",
            entries = merged.sortedByDescending { item -> item.watchedAtEpochMs }.map { item -> item.toPublicEntry() },
            authState = authState()
        )
    }

    override suspend fun markWatched(request: WatchHistoryRequest): WatchHistoryLabResult {
        val normalized = normalizeRequest(request)
        val existing = loadEntries()
        val updated = upsertEntry(existing, normalized.toLocalWatchedItem())
        saveEntries(updated)

        val syncedTrakt = syncTraktMark(normalized)
        val syncedSimkl = syncSimklMark(normalized)
        val entries = updated.sortedByDescending { item -> item.watchedAtEpochMs }.map { item -> item.toPublicEntry() }

        return WatchHistoryLabResult(
            statusMessage =
                "Marked watched locally. trakt=${syncStatusLabel(syncedTrakt, traktAccessToken(), traktClientId)} " +
                    "simkl=${syncStatusLabel(syncedSimkl, simklAccessToken(), simklClientId)}",
            entries = entries,
            authState = authState(),
            syncedToTrakt = syncedTrakt,
            syncedToSimkl = syncedSimkl
        )
    }

    override suspend fun unmarkWatched(request: WatchHistoryRequest): WatchHistoryLabResult {
        val normalized = normalizeRequest(request)
        val existing = loadEntries()
        val updated = removeEntry(existing, normalized)
        saveEntries(updated)

        val syncedTrakt = syncTraktUnmark(normalized)
        val syncedSimkl = syncSimklUnmark(normalized)
        val entries = updated.sortedByDescending { item -> item.watchedAtEpochMs }.map { item -> item.toPublicEntry() }

        return WatchHistoryLabResult(
            statusMessage =
                "Removed watched entry locally. trakt=${syncStatusLabel(syncedTrakt, traktAccessToken(), traktClientId)} " +
                    "simkl=${syncStatusLabel(syncedSimkl, simklAccessToken(), simklClientId)}",
            entries = entries,
            authState = authState(),
            syncedToTrakt = syncedTrakt,
            syncedToSimkl = syncedSimkl
        )
    }

    private fun syncStatusLabel(synced: Boolean, token: String, clientId: String): String {
        return when {
            synced -> "ok"
            token.isEmpty() -> "skip(no-token)"
            clientId.isEmpty() -> "skip(no-client-id)"
            else -> "failed"
        }
    }

    private fun normalizeRequest(request: WatchHistoryRequest): NormalizedWatchRequest {
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

        val remoteImdbId =
            when {
                contentId.startsWith("tt", ignoreCase = true) -> contentId.lowercase()
                request.remoteImdbId?.trim()?.startsWith("tt", ignoreCase = true) == true ->
                    request.remoteImdbId.trim().lowercase()
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
            remoteImdbId = remoteImdbId
        )
    }

    private fun loadEntries(): List<LocalWatchedItem> {
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

    private fun saveEntries(items: List<LocalWatchedItem>) {
        val array = JSONArray()
        items.forEach { item -> array.put(item.toJson()) }
        prefs.edit().putString(KEY_LOCAL_WATCHED_ITEMS, array.toString()).apply()
    }

    private fun upsertEntry(existing: List<LocalWatchedItem>, next: LocalWatchedItem): List<LocalWatchedItem> {
        val byKey = linkedMapOf<String, LocalWatchedItem>()
        existing.forEach { item ->
            byKey[watchedKey(item.contentId, item.season, item.episode)] = item
        }
        val key = watchedKey(next.contentId, next.season, next.episode)
        val current = byKey[key]
        if (current == null || next.watchedAtEpochMs >= current.watchedAtEpochMs) {
            byKey[key] = next
        }
        return byKey.values.toList()
    }

    private fun dedupeEntries(items: List<LocalWatchedItem>): List<LocalWatchedItem> {
        val byKey = linkedMapOf<String, LocalWatchedItem>()
        items.forEach { item ->
            val key = watchedKey(item.contentId, item.season, item.episode)
            val current = byKey[key]
            if (current == null || item.watchedAtEpochMs >= current.watchedAtEpochMs) {
                byKey[key] = item
            }
        }
        return byKey.values.toList()
    }

    private fun removeEntry(existing: List<LocalWatchedItem>, request: NormalizedWatchRequest): List<LocalWatchedItem> {
        val removalKey = watchedKey(request.contentId, request.season, request.episode)
        return existing.filterNot { item ->
            watchedKey(item.contentId, item.season, item.episode) == removalKey
        }
    }

    private fun watchedKey(contentId: String, season: Int?, episode: Int?): String {
        return "$contentId::${season ?: -1}::${episode ?: -1}"
    }

    private fun syncTraktMark(request: NormalizedWatchRequest): Boolean {
        val token = traktAccessToken()
        val imdbId = request.remoteImdbId
        if (token.isEmpty() || traktClientId.isEmpty() || imdbId == null) {
            return false
        }

        val watchedAtIso = Instant.ofEpochMilli(request.watchedAtEpochMs).toString()
        val body =
            if (request.contentType == MetadataLabMediaType.MOVIE) {
                JSONObject()
                    .put(
                        "movies",
                        JSONArray().put(
                            JSONObject()
                                .put("watched_at", watchedAtIso)
                                .put("ids", JSONObject().put("imdb", imdbId))
                        )
                    )
            } else {
                JSONObject()
                    .put(
                        "shows",
                        JSONArray().put(
                            JSONObject()
                                .put("ids", JSONObject().put("imdb", imdbId))
                                .put(
                                    "seasons",
                                    JSONArray().put(
                                        JSONObject()
                                            .put("number", request.season)
                                            .put(
                                                "episodes",
                                                JSONArray().put(
                                                    JSONObject()
                                                        .put("number", request.episode)
                                                        .put("watched_at", watchedAtIso)
                                                )
                                            )
                                    )
                                )
                        )
                    )
            }

        return traktPost("/sync/history", body)
    }

    private fun syncTraktUnmark(request: NormalizedWatchRequest): Boolean {
        val token = traktAccessToken()
        val imdbId = request.remoteImdbId
        if (token.isEmpty() || traktClientId.isEmpty() || imdbId == null) {
            return false
        }

        val body =
            if (request.contentType == MetadataLabMediaType.MOVIE) {
                JSONObject()
                    .put(
                        "movies",
                        JSONArray().put(
                            JSONObject().put("ids", JSONObject().put("imdb", imdbId))
                        )
                    )
            } else {
                JSONObject()
                    .put(
                        "shows",
                        JSONArray().put(
                            JSONObject()
                                .put("ids", JSONObject().put("imdb", imdbId))
                                .put(
                                    "seasons",
                                    JSONArray().put(
                                        JSONObject()
                                            .put("number", request.season)
                                            .put(
                                                "episodes",
                                                JSONArray().put(
                                                    JSONObject().put("number", request.episode)
                                                )
                                            )
                                    )
                                )
                        )
                    )
            }

        return traktPost("/sync/history/remove", body)
    }

    private fun traktPost(path: String, payload: JSONObject): Boolean {
        val token = traktAccessToken()
        if (token.isEmpty() || traktClientId.isEmpty()) {
            return false
        }

        val responseCode =
            postJson(
                url = "https://api.trakt.tv$path",
                headers =
                    mapOf(
                        "Authorization" to "Bearer $token",
                        "trakt-api-version" to "2",
                        "trakt-api-key" to traktClientId,
                        "Content-Type" to "application/json",
                        "Accept" to "application/json"
                    ),
                payload = payload
            )

        return responseCode in 200..299 || responseCode == 409
    }

    private fun syncSimklMark(request: NormalizedWatchRequest): Boolean {
        val token = simklAccessToken()
        val imdbId = request.remoteImdbId
        if (token.isEmpty() || simklClientId.isEmpty() || imdbId == null) {
            return false
        }

        val watchedAtIso = Instant.ofEpochMilli(request.watchedAtEpochMs).toString()
        val body =
            if (request.contentType == MetadataLabMediaType.MOVIE) {
                JSONObject()
                    .put(
                        "movies",
                        JSONArray().put(
                            JSONObject()
                                .put("ids", JSONObject().put("imdb", imdbId))
                                .put("watched_at", watchedAtIso)
                        )
                    )
            } else {
                JSONObject()
                    .put(
                        "shows",
                        JSONArray().put(
                            JSONObject()
                                .put("ids", JSONObject().put("imdb", imdbId))
                                .put(
                                    "seasons",
                                    JSONArray().put(
                                        JSONObject()
                                            .put("number", request.season)
                                            .put(
                                                "episodes",
                                                JSONArray().put(
                                                    JSONObject()
                                                        .put("number", request.episode)
                                                        .put("watched_at", watchedAtIso)
                                                )
                                            )
                                    )
                                )
                        )
                    )
            }

        return simklPost("/sync/history", body)
    }

    private fun syncSimklUnmark(request: NormalizedWatchRequest): Boolean {
        val token = simklAccessToken()
        val imdbId = request.remoteImdbId
        if (token.isEmpty() || simklClientId.isEmpty() || imdbId == null) {
            return false
        }

        val body =
            if (request.contentType == MetadataLabMediaType.MOVIE) {
                JSONObject()
                    .put(
                        "movies",
                        JSONArray().put(
                            JSONObject().put("ids", JSONObject().put("imdb", imdbId))
                        )
                    )
            } else {
                JSONObject()
                    .put(
                        "shows",
                        JSONArray().put(
                            JSONObject()
                                .put("ids", JSONObject().put("imdb", imdbId))
                                .put(
                                    "seasons",
                                    JSONArray().put(
                                        JSONObject()
                                            .put("number", request.season)
                                            .put(
                                                "episodes",
                                                JSONArray().put(
                                                    JSONObject().put("number", request.episode)
                                                )
                                            )
                                    )
                                )
                        )
                    )
            }

        return simklPost("/sync/history/remove", body)
    }

    private fun simklPost(path: String, payload: JSONObject): Boolean {
        val token = simklAccessToken()
        if (token.isEmpty() || simklClientId.isEmpty()) {
            return false
        }

        val responseCode =
            postJson(
                url = "https://api.simkl.com$path",
                headers =
                    mapOf(
                        "Authorization" to "Bearer $token",
                        "simkl-api-key" to simklClientId,
                        "Content-Type" to "application/json",
                        "Accept" to "application/json"
                    ),
                payload = payload
            )

        return responseCode in 200..299 || responseCode == 409
    }

    private fun postJson(url: String, headers: Map<String, String>, payload: JSONObject): Int? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "POST"
            doOutput = true
            headers.forEach { (key, value) ->
                setRequestProperty(key, value)
            }
        }

        return runCatching {
            connection.outputStream.bufferedWriter().use { writer ->
                writer.write(payload.toString())
            }
            connection.responseCode
        }.getOrNull().also {
            runCatching {
                connection.inputStream?.close()
                connection.errorStream?.close()
            }
            connection.disconnect()
        }
    }

    private fun traktAccessToken(): String {
        return prefs.getString(KEY_TRAKT_TOKEN, null)?.trim().orEmpty()
    }

    private fun simklAccessToken(): String {
        return prefs.getString(KEY_SIMKL_TOKEN, null)?.trim().orEmpty()
    }

    companion object {
        private const val PREFS_NAME = "watch_history_lab"
        private const val KEY_LOCAL_WATCHED_ITEMS = "@user:local:watched_items"
        private const val KEY_TRAKT_TOKEN = "trakt_access_token"
        private const val KEY_SIMKL_TOKEN = "simkl_access_token"
    }
}

private data class NormalizedWatchRequest(
    val contentId: String,
    val contentType: MetadataLabMediaType,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val watchedAtEpochMs: Long,
    val remoteImdbId: String?
) {
    fun toLocalWatchedItem(): LocalWatchedItem {
        return LocalWatchedItem(
            contentId = contentId,
            contentType = contentType,
            title = title,
            season = season,
            episode = episode,
            watchedAtEpochMs = watchedAtEpochMs
        )
    }
}

private data class LocalWatchedItem(
    val contentId: String,
    val contentType: MetadataLabMediaType,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val watchedAtEpochMs: Long
) {
    fun toPublicEntry(): WatchHistoryEntry {
        return WatchHistoryEntry(
            contentId = contentId,
            contentType = contentType,
            title = title,
            season = season,
            episode = episode,
            watchedAtEpochMs = watchedAtEpochMs
        )
    }

    fun toJson(): JSONObject {
        return JSONObject()
            .put("content_id", contentId)
            .put("content_type", contentType.value)
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
                watchedAtEpochMs = watchedAt
            )
        }
    }
}

package com.crispy.tv.watchhistory.simkl

import android.util.Log
import com.crispy.tv.domain.metadata.normalizeNuvioMediaId
import com.crispy.tv.player.ContinueWatchingEntry
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.ProviderLibraryFolder
import com.crispy.tv.player.ProviderLibraryItem
import com.crispy.tv.player.WatchHistoryRequest
import com.crispy.tv.player.WatchProvider
import com.crispy.tv.watchhistory.CONTINUE_WATCHING_COMPLETION_PERCENT
import com.crispy.tv.watchhistory.CONTINUE_WATCHING_MIN_PROGRESS_PERCENT
import com.crispy.tv.watchhistory.STALE_PLAYBACK_WINDOW_MS
import com.crispy.tv.watchhistory.auth.ProviderSessionStore
import com.crispy.tv.watchhistory.local.NormalizedWatchRequest
import com.crispy.tv.watchhistory.provider.NormalizedContentRequest
import com.crispy.tv.watchhistory.provider.WatchHistoryProvider
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Locale

internal class SimklWatchHistoryProvider(
    private val simklService: SimklService,
    private val sessionStore: ProviderSessionStore,
    private val simklClientId: String,
) : WatchHistoryProvider {
    override val source: WatchProvider = WatchProvider.SIMKL

    override fun hasClientId(): Boolean = simklClientId.isNotBlank()

    override fun hasAccessToken(): Boolean = sessionStore.simklAccessToken().isNotEmpty()

    override suspend fun markWatched(request: NormalizedWatchRequest): Boolean {
        val payload = buildHistoryPayload(request, watchedAtEpochMs = request.watchedAtEpochMs) ?: return false
        return simklService.addToHistory(payload)
    }

    override suspend fun unmarkWatched(request: NormalizedWatchRequest): Boolean {
        val payload = buildHistoryPayload(request, watchedAtEpochMs = null) ?: return false
        return simklService.removeFromHistory(payload)
    }

    override suspend fun setInWatchlist(request: WatchHistoryRequest, inWatchlist: Boolean): Boolean {
        val normalized = normalizeContentRequest(request)
        val imdbId = normalized.remoteImdbId ?: return false
        val typeKey = typeKeyFor(normalized.contentType) ?: return false

        return if (inWatchlist) {
            simklService.addToList(typeKey = typeKey, imdbId = imdbId, status = STATUS_PLAN_TO_WATCH)
        } else {
            simklService.removeFromList(typeKey = typeKey, imdbId = imdbId)
        }
    }

    override suspend fun setRating(request: WatchHistoryRequest, rating: Int?): Boolean {
        if (rating == null) return false

        val normalized = normalizeContentRequest(request)
        val imdbId = normalized.remoteImdbId ?: return false
        val typeKey = typeKeyFor(normalized.contentType) ?: return false

        return simklService.addRating(typeKey = typeKey, imdbId = imdbId, rating = rating)
    }

    override suspend fun removeFromPlayback(playbackId: String): Boolean = false

    override suspend fun listContinueWatching(nowMs: Long): List<ContinueWatchingEntry> {
        val playback = simklService.getPlaybackStatus()
        if (playback.length() == 0) return emptyList()

        val staleCutoff = nowMs - STALE_PLAYBACK_WINDOW_MS
        val results = ArrayList<ContinueWatchingEntry>(playback.length())

        for (i in 0 until playback.length()) {
            val item = playback.optJSONObject(i) ?: continue
            val entry = parsePlaybackItem(item, nowMs) ?: continue

            if (entry.contentId.isBlank()) continue
            if (entry.lastUpdatedEpochMs < staleCutoff) continue

            val progress = entry.progressPercent
            if (progress < CONTINUE_WATCHING_MIN_PROGRESS_PERCENT) continue
            if (progress >= CONTINUE_WATCHING_COMPLETION_PERCENT) continue

            results += entry
        }

        return results.sortedByDescending { it.lastUpdatedEpochMs }
    }

    override suspend fun listProviderLibrary(limitPerFolder: Int): Pair<List<ProviderLibraryFolder>, List<ProviderLibraryItem>> {
        val folderItems = linkedMapOf<String, MutableList<ProviderLibraryItem>>()

        val continueWatching = listContinueWatching(nowMs = System.currentTimeMillis())
        if (continueWatching.isNotEmpty()) {
            val items = continueWatching.map { it.toLibraryItem(folderId = FOLDER_CONTINUE_WATCHING) }
            addFolder(folderItems, FOLDER_CONTINUE_WATCHING, items, limitPerFolder)
        }

        for (status in STATUSES) {
            addAllItemsFolderFromEndpoint(
                folderItems = folderItems,
                status = status,
                typeId = "movies",
                contentType = MetadataLabMediaType.MOVIE,
                limitPerFolder = limitPerFolder,
            )
            addAllItemsFolderFromEndpoint(
                folderItems = folderItems,
                status = status,
                typeId = "shows",
                contentType = MetadataLabMediaType.SERIES,
                limitPerFolder = limitPerFolder,
            )
            addAllItemsFolderFromEndpoint(
                folderItems = folderItems,
                status = status,
                typeId = "anime",
                contentType = MetadataLabMediaType.SERIES,
                limitPerFolder = limitPerFolder,
            )
        }

        val ratingsResponse = simklService.getRatingsResponse()
        if (ratingsResponse is JSONObject) {
            val ratingItems =
                buildList {
                    addAll(parseRatingsArray(ratingsResponse.optJSONArray("movies"), MetadataLabMediaType.MOVIE))
                    addAll(parseRatingsArray(ratingsResponse.optJSONArray("shows"), MetadataLabMediaType.SERIES))
                    addAll(parseRatingsArray(ratingsResponse.optJSONArray("anime"), MetadataLabMediaType.SERIES))
                }
            addFolder(folderItems, FOLDER_RATINGS, ratingItems, limitPerFolder)
        }

        val folders =
            folderItems.mapNotNull { (id, items) ->
                if (items.isEmpty()) return@mapNotNull null
                ProviderLibraryFolder(
                    id = id,
                    label = folderLabel(id),
                    provider = source,
                    itemCount = items.size,
                )
            }

        val items = folderItems.values.flatten()
        return folders to items
    }

    override suspend fun listRecommendations(limit: Int): List<ProviderLibraryItem> {
        return emptyList()
    }

    private fun normalizeContentRequest(request: WatchHistoryRequest): NormalizedContentRequest {
        val normalizedId = normalizeNuvioMediaId(request.contentId)
        val contentId = normalizedId.contentId.trim()
        val remoteImdbId = normalizedImdbIdOrNull(contentId) ?: normalizedImdbIdOrNull(request.remoteImdbId)
        val title = request.title?.trim().orEmpty().ifBlank { contentId }
        return NormalizedContentRequest(
            contentId = contentId,
            contentType = request.contentType,
            title = title,
            remoteImdbId = remoteImdbId,
        )
    }

    private fun buildHistoryPayload(request: NormalizedWatchRequest, watchedAtEpochMs: Long?): JSONObject? {
        val ids = buildIds(request.contentId, request.remoteImdbId) ?: return null

        return when (request.contentType) {
            MetadataLabMediaType.MOVIE -> {
                val item = JSONObject().put("ids", ids)
                if (watchedAtEpochMs != null) {
                    item.put("watched_at", toIsoString(watchedAtEpochMs))
                }
                JSONObject().put("movies", JSONArray().put(item))
            }

            MetadataLabMediaType.SERIES -> {
                val season = request.season ?: return null
                val episode = request.episode ?: return null
                if (season <= 0 || episode <= 0) return null

                val episodeObj = JSONObject().put("number", episode)
                if (watchedAtEpochMs != null) {
                    episodeObj.put("watched_at", toIsoString(watchedAtEpochMs))
                }

                val seasonObj =
                    JSONObject()
                        .put("number", season)
                        .put("episodes", JSONArray().put(episodeObj))

                val showObj =
                    JSONObject()
                        .put("ids", ids)
                        .put("seasons", JSONArray().put(seasonObj))

                JSONObject().put("shows", JSONArray().put(showObj))
            }
        }
    }

    private fun buildIds(contentId: String, remoteImdbId: String?): JSONObject? {
        val ids = JSONObject()

        val imdb = normalizedImdbIdOrNull(remoteImdbId) ?: normalizedImdbIdOrNull(contentId)
        if (imdb != null) {
            ids.put("imdb", imdb)
        }

        val tmdb = tmdbIdFromContentId(contentId)
        if (tmdb != null) {
            ids.put("tmdb", tmdb)
        }

        return ids.takeIf { it.length() > 0 }
    }

    private fun tmdbIdFromContentId(contentId: String): Long? {
        val normalized = normalizeNuvioMediaId(contentId).contentId.trim()
        if (!normalized.startsWith("tmdb:", ignoreCase = true)) return null
        return normalized.substringAfter(':').substringBefore(':').toLongOrNull()
    }

    private fun parsePlaybackItem(item: JSONObject, nowMs: Long): ContinueWatchingEntry? {
        val type = item.optString("type").trim().lowercase(Locale.US)
        val progress = item.optDouble("progress", -1.0)
        if (progress < 0.0) return null

        val pausedAtEpochMs = parseIsoToEpochMs(item.optString("paused_at")) ?: nowMs
        val playbackId = item.opt("id")?.toString()?.trim()?.ifBlank { null }

        return when (type) {
            "movie" -> {
                val movie = item.optJSONObject("movie") ?: return null
                val ids = movie.optJSONObject("ids")
                val contentId = contentIdFromIds(ids)
                if (contentId.isEmpty()) return null
                val title = movie.optString("title").trim().ifBlank { contentId }
                ContinueWatchingEntry(
                    contentId = contentId,
                    contentType = MetadataLabMediaType.MOVIE,
                    title = title,
                    season = null,
                    episode = null,
                    progressPercent = progress,
                    lastUpdatedEpochMs = pausedAtEpochMs,
                    provider = source,
                    providerPlaybackId = playbackId,
                )
            }

            "episode" -> {
                val show = item.optJSONObject("show") ?: return null
                val episodeObj = item.optJSONObject("episode") ?: return null

                val ids = show.optJSONObject("ids")
                val contentId = contentIdFromIds(ids)
                if (contentId.isEmpty()) return null

                val title = show.optString("title").trim().ifBlank { contentId }

                val season = episodeObj.optInt("season", 0).takeIf { it > 0 }
                val episode =
                    episodeObj.optInt("episode", 0)
                        .takeIf { it > 0 }
                        ?: episodeObj.optInt("number", 0).takeIf { it > 0 }

                ContinueWatchingEntry(
                    contentId = contentId,
                    contentType = MetadataLabMediaType.SERIES,
                    title = title,
                    season = season,
                    episode = episode,
                    progressPercent = progress,
                    lastUpdatedEpochMs = pausedAtEpochMs,
                    provider = source,
                    providerPlaybackId = playbackId,
                )
            }

            else -> null
        }
    }

    private fun addAllItemsFolder(
        folderItems: LinkedHashMap<String, MutableList<ProviderLibraryItem>>,
        status: String,
        typeId: String,
        contentType: MetadataLabMediaType,
        array: JSONArray?,
        limitPerFolder: Int,
    ) {
        if (array == null || array.length() == 0) return

        val folderId = "$status-$typeId"
        val items = parseAllItemsArray(array = array, folderId = folderId, contentType = contentType)
        addFolder(folderItems, folderId, items, limitPerFolder)
    }

    private suspend fun addAllItemsFolderFromEndpoint(
        folderItems: LinkedHashMap<String, MutableList<ProviderLibraryItem>>,
        status: String,
        typeId: String,
        contentType: MetadataLabMediaType,
        limitPerFolder: Int,
    ) {
        val response = simklService.getAllItemsResponse(type = typeId, status = status)
        val array =
            when (response) {
                is JSONArray -> response
                is JSONObject -> response.optJSONArray(typeId)
                else -> null
            }

        addAllItemsFolder(
            folderItems = folderItems,
            status = status,
            typeId = typeId,
            contentType = contentType,
            array = array,
            limitPerFolder = limitPerFolder,
        )
    }

    private fun parseAllItemsArray(array: JSONArray, folderId: String, contentType: MetadataLabMediaType): List<ProviderLibraryItem> {
        val nowMs = System.currentTimeMillis()
        val results = ArrayList<ProviderLibraryItem>(array.length())

        for (i in 0 until array.length()) {
            val wrapper = array.optJSONObject(i) ?: continue

            val content =
                wrapper.optJSONObject("movie")
                    ?: wrapper.optJSONObject("show")
                    ?: wrapper.optJSONObject("anime")
                    ?: wrapper

            val ids = content.optJSONObject("ids") ?: wrapper.optJSONObject("ids")
            val contentId = contentIdFromIds(ids)
            if (contentId.isEmpty()) continue

            val title = content.optString("title").trim().ifBlank { contentId }
            val posterUrl = content.optString("poster").trim().ifBlank { null }
            val backdropUrl = content.optString("fanart").trim().ifBlank { null }

            val addedAtEpochMs =
                parseIsoToEpochMs(wrapper.optString("last_watched_at"))
                    ?: parseIsoToEpochMs(wrapper.optString("added_to_watchlist_at"))
                    ?: nowMs

            results +=
                ProviderLibraryItem(
                    provider = source,
                    folderId = folderId,
                    contentId = contentId,
                    contentType = contentType,
                    title = title,
                    posterUrl = posterUrl,
                    backdropUrl = backdropUrl,
                    addedAtEpochMs = addedAtEpochMs,
                )
        }

        return results
    }

    private fun parseRatingsArray(array: JSONArray?, contentType: MetadataLabMediaType): List<ProviderLibraryItem> {
        if (array == null || array.length() == 0) return emptyList()

        val nowMs = System.currentTimeMillis()
        val results = ArrayList<ProviderLibraryItem>(array.length())

        for (i in 0 until array.length()) {
            val wrapper = array.optJSONObject(i) ?: continue

            val content =
                wrapper.optJSONObject("movie")
                    ?: wrapper.optJSONObject("show")
                    ?: wrapper.optJSONObject("anime")
                    ?: wrapper

            val ids = content.optJSONObject("ids") ?: wrapper.optJSONObject("ids")
            val contentId = contentIdFromIds(ids)
            if (contentId.isEmpty()) continue

            val title = content.optString("title").trim().ifBlank { contentId }
            val posterUrl = content.optString("poster").trim().ifBlank { null }
            val backdropUrl = content.optString("fanart").trim().ifBlank { null }

            val addedAtEpochMs = parseIsoToEpochMs(wrapper.optString("rated_at")) ?: nowMs

            results +=
                ProviderLibraryItem(
                    provider = source,
                    folderId = FOLDER_RATINGS,
                    contentId = contentId,
                    contentType = contentType,
                    title = title,
                    posterUrl = posterUrl,
                    backdropUrl = backdropUrl,
                    addedAtEpochMs = addedAtEpochMs,
                )
        }

        return results
    }

    private fun addFolder(
        folderItems: LinkedHashMap<String, MutableList<ProviderLibraryItem>>,
        folderId: String,
        items: List<ProviderLibraryItem>,
        limitPerFolder: Int,
    ) {
        if (items.isEmpty()) return
        val limit = limitPerFolder.coerceAtLeast(1)
        val bucket = folderItems.getOrPut(folderId) { mutableListOf() }
        bucket.addAll(items.take(limit))
    }

    private fun ContinueWatchingEntry.toLibraryItem(folderId: String): ProviderLibraryItem {
        return ProviderLibraryItem(
            provider = source,
            folderId = folderId,
            contentId = contentId,
            contentType = contentType,
            title = title,
            season = season,
            episode = episode,
            addedAtEpochMs = lastUpdatedEpochMs,
        )
    }

    private fun contentIdFromIds(ids: JSONObject?): String {
        if (ids == null) return ""

        val imdb = normalizedImdbIdOrNull(ids.optString("imdb"))
        if (imdb != null) return imdb

        val tmdb =
            when (val raw = ids.opt("tmdb")) {
                is Number -> raw.toLong().toString()
                is String -> raw.trim().toLongOrNull()?.toString()
                else -> null
            }

        return if (tmdb != null) "tmdb:$tmdb" else ""
    }

    private fun normalizedImdbIdOrNull(raw: String?): String? {
        val value = raw?.trim()?.lowercase(Locale.US).orEmpty()
        if (value.isBlank()) return null

        val candidate =
            when {
                value.startsWith("tt") -> value
                value.startsWith("imdb:") -> value.substringAfter("imdb:")
                value.all { it.isDigit() } -> "tt$value"
                else -> return null
            }

        if (!candidate.startsWith("tt")) return null
        if (candidate.length < 4) return null
        if (!candidate.substring(2).all { it.isDigit() }) return null

        return candidate
    }

    private fun toIsoString(epochMs: Long): String {
        return Instant.ofEpochMilli(epochMs).toString()
    }

    private fun parseIsoToEpochMs(raw: String?): Long? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        return runCatching { Instant.parse(value).toEpochMilli() }
            .onFailure { Log.w(TAG, "Failed to parse ISO timestamp: $value", it) }
            .getOrNull()
    }

    private fun typeKeyFor(contentType: MetadataLabMediaType): String? {
        return when (contentType) {
            MetadataLabMediaType.MOVIE -> "movies"
            MetadataLabMediaType.SERIES -> "shows"
        }
    }

    private fun folderLabel(id: String): String {
        return id.replace('-', ' ')
            .replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase(Locale.US) else c.toString() }
    }

    private companion object {
        private const val TAG = "SimklWatchHistoryProvider"

        private const val FOLDER_CONTINUE_WATCHING = "continue-watching"
        private const val FOLDER_RATINGS = "ratings"

        private const val STATUS_PLAN_TO_WATCH = "plantowatch"

        private val STATUSES = listOf("watching", "plantowatch", "completed", "hold", "dropped")
    }
}

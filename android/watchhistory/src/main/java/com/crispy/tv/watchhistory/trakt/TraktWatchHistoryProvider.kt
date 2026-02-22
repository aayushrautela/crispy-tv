package com.crispy.tv.watchhistory.trakt

import android.util.Log
import com.crispy.tv.domain.metadata.normalizeNuvioMediaId
import com.crispy.tv.player.ContinueWatchingEntry
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.ProviderComment
import com.crispy.tv.player.ProviderCommentQuery
import com.crispy.tv.player.ProviderCommentResult
import com.crispy.tv.player.ProviderCommentScope
import com.crispy.tv.player.ProviderLibraryFolder
import com.crispy.tv.player.ProviderLibraryItem
import com.crispy.tv.player.WatchHistoryRequest
import com.crispy.tv.player.WatchProvider
import com.crispy.tv.watchhistory.CONTINUE_WATCHING_COMPLETION_PERCENT
import com.crispy.tv.watchhistory.CONTINUE_WATCHING_MIN_PROGRESS_PERCENT
import com.crispy.tv.watchhistory.CONTINUE_WATCHING_PLAYBACK_LIMIT
import com.crispy.tv.watchhistory.CONTINUE_WATCHING_UPNEXT_SHOW_LIMIT
import com.crispy.tv.watchhistory.STALE_PLAYBACK_WINDOW_MS
import com.crispy.tv.watchhistory.auth.ProviderSessionStore
import com.crispy.tv.watchhistory.local.NormalizedWatchRequest
import com.crispy.tv.watchhistory.provider.NormalizedContentRequest
import com.crispy.tv.watchhistory.provider.WatchHistoryProvider
import java.time.Instant
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal class TraktWatchHistoryProvider(
    private val traktApi: TraktApi,
    private val sessionStore: ProviderSessionStore,
    private val traktClientId: String,
) : WatchHistoryProvider {
    override val source: WatchProvider = WatchProvider.TRAKT

    override fun hasClientId(): Boolean {
        return traktClientId.isNotBlank()
    }

    override fun hasAccessToken(): Boolean {
        return sessionStore.traktAccessToken().isNotEmpty()
    }

    override suspend fun markWatched(request: NormalizedWatchRequest): Boolean {
        return syncTraktMark(request)
    }

    override suspend fun unmarkWatched(request: NormalizedWatchRequest): Boolean {
        return syncTraktUnmark(request)
    }

    override suspend fun setInWatchlist(request: WatchHistoryRequest, inWatchlist: Boolean): Boolean {
        return syncTraktWatchlist(normalizeContentRequest(request), inWatchlist)
    }

    override suspend fun setRating(request: WatchHistoryRequest, rating: Int?): Boolean {
        return syncTraktRating(normalizeContentRequest(request), rating)
    }

    override suspend fun removeFromPlayback(playbackId: String): Boolean {
        return syncTraktRemovePlayback(playbackId)
    }

    override suspend fun listContinueWatching(nowMs: Long): List<ContinueWatchingEntry> {
        return fetchTraktContinueWatching(nowMs)
    }

    override suspend fun listProviderLibrary(limitPerFolder: Int): Pair<List<ProviderLibraryFolder>, List<ProviderLibraryItem>> {
        return fetchTraktLibrary(limitPerFolder)
    }

    override suspend fun listRecommendations(limit: Int): List<ProviderLibraryItem> {
        return fetchTraktRecommendationsMixed(limit)
    }

    override suspend fun fetchComments(query: ProviderCommentQuery): ProviderCommentResult {
        if (sessionStore.traktAccessToken().isBlank() || traktClientId.isBlank()) {
            return ProviderCommentResult(statusMessage = "Trakt is not connected.")
        }

        val traktType =
            when (query.scope) {
                ProviderCommentScope.MOVIE -> "movie"
                ProviderCommentScope.SHOW, ProviderCommentScope.SEASON, ProviderCommentScope.EPISODE -> "show"
            }

        val traktId = resolveTraktId(query.imdbId, query.tmdbId, traktType)
            ?: return ProviderCommentResult(statusMessage = "Unable to resolve Trakt id for comments.")

        val page = query.page.coerceAtLeast(1)
        val limit = query.limit.coerceIn(1, 100)
        val endpoint =
            when (query.scope) {
                ProviderCommentScope.MOVIE -> "/movies/$traktId/comments?page=$page&limit=$limit"
                ProviderCommentScope.SHOW -> "/shows/$traktId/comments?page=$page&limit=$limit"
                ProviderCommentScope.SEASON -> {
                    val season = query.season ?: return ProviderCommentResult(statusMessage = "Season is required.")
                    "/shows/$traktId/seasons/$season/comments?page=$page&limit=$limit"
                }

                ProviderCommentScope.EPISODE -> {
                    val season = query.season ?: return ProviderCommentResult(statusMessage = "Season is required.")
                    val episode = query.episode ?: return ProviderCommentResult(statusMessage = "Episode is required.")
                    "/shows/$traktId/seasons/$season/episodes/$episode/comments?page=$page&limit=$limit"
                }
            }

        val payload = traktGetArray(endpoint) ?: return ProviderCommentResult(statusMessage = "No comments found.")
        val comments =
            buildList {
                for (index in 0 until payload.length()) {
                    val obj = payload.optJSONObject(index) ?: continue
                    val id = obj.opt("id")?.toString()?.trim().orEmpty()
                    if (id.isBlank()) continue
                    val user = obj.optJSONObject("user")
                    val username = user?.optString("username")?.trim().orEmpty().ifBlank { "unknown" }
                    val comment = obj.optString("comment").trim()
                    if (comment.isEmpty()) continue

                    add(
                        ProviderComment(
                            id = id,
                            username = username,
                            text = comment,
                            spoiler = obj.optBoolean("spoiler", false),
                            createdAtEpochMs = parseIsoToEpochMs(obj.optString("created_at")) ?: System.currentTimeMillis(),
                            likes = obj.optInt("likes", 0),
                        )
                    )
                }
            }

        return ProviderCommentResult(
            statusMessage = if (comments.isEmpty()) "No comments found." else "Loaded ${comments.size} comments.",
            comments = comments,
        )
    }

    private data class TraktNextEpisode(
        val season: Int,
        val episode: Int,
        val title: String?,
    )

    private suspend fun fetchTraktContinueWatching(nowMs: Long): List<ContinueWatchingEntry> {
        val payload = traktGetArray("/sync/playback")
            ?: throw IllegalStateException("Trakt /sync/playback returned null")

        val staleCutoff = nowMs - STALE_PLAYBACK_WINDOW_MS
        val nextEpisodeCache = mutableMapOf<String, TraktNextEpisode?>()

        suspend fun nextEpisodeForShow(showTraktId: String): TraktNextEpisode? {
            val id = showTraktId.trim()
            if (id.isEmpty()) return null
            if (nextEpisodeCache.containsKey(id)) return nextEpisodeCache[id]

            val endpoint = "/shows/$id/progress/watched?hidden=true&specials=false&count_specials=false"
            val obj = traktGetObject(endpoint)
            val next = obj?.optJSONObject("next_episode")
            val season = next?.optInt("season", 0) ?: 0
            val number = next?.optInt("number", 0) ?: 0
            val result =
                if (season <= 0 || number <= 0) {
                    null
                } else {
                    TraktNextEpisode(
                        season = season,
                        episode = number,
                        title = next?.optString("title")?.trim()?.ifEmpty { null },
                    )
                }
            nextEpisodeCache[id] = result
            return result
        }

        val playbackItems =
            buildList<Pair<Long, JSONObject>> {
                for (index in 0 until payload.length()) {
                    val obj = payload.optJSONObject(index) ?: continue
                    val pausedAt = parseIsoToEpochMs(obj.optString("paused_at")) ?: nowMs
                    add(pausedAt to obj)
                }
            }.sortedByDescending { (pausedAt, _) -> pausedAt }
                .take(CONTINUE_WATCHING_PLAYBACK_LIMIT)

        val existingSeriesTraktIds = mutableSetOf<String>()
        val playbackEntries = mutableListOf<ContinueWatchingEntry>()

        for ((pausedAt, obj) in playbackItems) {
            val type = obj.optString("type").trim().lowercase(Locale.US)
            val progress = obj.optDouble("progress", -1.0)
            if (progress < 0) continue
            if (pausedAt < staleCutoff) continue
            if (progress < CONTINUE_WATCHING_MIN_PROGRESS_PERCENT) continue

            if (type == "movie") {
                if (progress >= CONTINUE_WATCHING_COMPLETION_PERCENT) continue

                val movie = obj.optJSONObject("movie") ?: continue
                val contentId = normalizedContentIdFromIds(movie.optJSONObject("ids"))
                if (contentId.isEmpty()) continue
                val title = movie.optString("title").trim().ifEmpty { contentId }
                playbackEntries.add(
                    ContinueWatchingEntry(
                        contentId = contentId,
                        contentType = MetadataLabMediaType.MOVIE,
                        title = title,
                        season = null,
                        episode = null,
                        progressPercent = progress,
                        lastUpdatedEpochMs = pausedAt,
                        provider = WatchProvider.TRAKT,
                        providerPlaybackId = obj.opt("id")?.toString()?.trim()?.ifEmpty { null },
                        isUpNextPlaceholder = false,
                    )
                )
                continue
            }

            if (type == "episode") {
                val episode = obj.optJSONObject("episode") ?: continue
                val show = obj.optJSONObject("show") ?: continue
                val ids = show.optJSONObject("ids")
                val contentId = normalizedContentIdFromIds(ids)
                if (contentId.isEmpty()) continue

                val showTraktId = ids?.opt("trakt")?.toString()?.trim().orEmpty()
                if (showTraktId.isNotEmpty()) {
                    existingSeriesTraktIds.add(showTraktId)
                }

                val episodeSeason = episode.optInt("season", 0).takeIf { it > 0 }
                val episodeNumber = episode.optInt("number", 0).takeIf { it > 0 }
                val showTitle = show.optString("title").trim().ifEmpty { contentId }
                val episodeTitle = episode.optString("title").trim()
                val title = if (episodeTitle.isBlank()) showTitle else "$showTitle - $episodeTitle"

                if (progress >= CONTINUE_WATCHING_COMPLETION_PERCENT) {
                    if (showTraktId.isBlank()) continue
                    val next = nextEpisodeForShow(showTraktId) ?: continue
                    playbackEntries.add(
                        ContinueWatchingEntry(
                            contentId = contentId,
                            contentType = MetadataLabMediaType.SERIES,
                            title = showTitle,
                            season = next.season,
                            episode = next.episode,
                            progressPercent = 0.0,
                            lastUpdatedEpochMs = pausedAt,
                            provider = WatchProvider.TRAKT,
                            providerPlaybackId = obj.opt("id")?.toString()?.trim()?.ifEmpty { null },
                            isUpNextPlaceholder = true,
                        )
                    )
                    continue
                }

                playbackEntries.add(
                    ContinueWatchingEntry(
                        contentId = contentId,
                        contentType = MetadataLabMediaType.SERIES,
                        title = title,
                        season = episodeSeason,
                        episode = episodeNumber,
                        progressPercent = progress,
                        lastUpdatedEpochMs = pausedAt,
                        provider = WatchProvider.TRAKT,
                        providerPlaybackId = obj.opt("id")?.toString()?.trim()?.ifEmpty { null },
                        isUpNextPlaceholder = false,
                    )
                )
            }
        }

        val existingSeriesIds =
            playbackEntries
                .asSequence()
                .filter { it.contentType == MetadataLabMediaType.SERIES }
                .map { it.contentId.lowercase(Locale.US) }
                .toSet()

        val watchedShows = traktGetArray("/sync/watched/shows") ?: return playbackEntries

        data class WatchedShowCandidate(
            val contentId: String,
            val traktId: String,
            val title: String,
            val lastWatchedAtMs: Long,
        )

        val candidateBuffer = mutableListOf<WatchedShowCandidate>()
        for (index in 0 until watchedShows.length()) {
            val obj = watchedShows.optJSONObject(index) ?: continue
            val lastWatchedAt = parseIsoToEpochMs(obj.optString("last_watched_at")) ?: continue
            if (lastWatchedAt < staleCutoff) continue

            val show = obj.optJSONObject("show") ?: continue
            val ids = show.optJSONObject("ids")
            val contentId = normalizedContentIdFromIds(ids)
            if (contentId.isEmpty()) continue
            if (contentId.lowercase(Locale.US) in existingSeriesIds) continue

            val traktId = ids?.opt("trakt")?.toString()?.trim().orEmpty()
            if (traktId.isEmpty()) continue
            if (existingSeriesTraktIds.contains(traktId)) continue

            val title = show.optString("title").trim().ifEmpty { contentId }
            candidateBuffer.add(
                WatchedShowCandidate(
                    contentId = contentId,
                    traktId = traktId,
                    title = title,
                    lastWatchedAtMs = lastWatchedAt,
                )
            )
        }

        val candidates =
            candidateBuffer
                .sortedByDescending { it.lastWatchedAtMs }
                .distinctBy { it.contentId.lowercase(Locale.US) }
                .take(CONTINUE_WATCHING_UPNEXT_SHOW_LIMIT)

        val upNextFromWatchedShows = mutableListOf<ContinueWatchingEntry>()
        for (candidate in candidates) {
            val next = nextEpisodeForShow(candidate.traktId) ?: continue
            upNextFromWatchedShows.add(
                ContinueWatchingEntry(
                    contentId = candidate.contentId,
                    contentType = MetadataLabMediaType.SERIES,
                    title = candidate.title,
                    season = next.season,
                    episode = next.episode,
                    progressPercent = 0.0,
                    lastUpdatedEpochMs = candidate.lastWatchedAtMs,
                    provider = WatchProvider.TRAKT,
                    providerPlaybackId = null,
                    isUpNextPlaceholder = true,
                )
            )
        }

        return playbackEntries + upNextFromWatchedShows
    }

    private suspend fun fetchTraktLibrary(limitPerFolder: Int): Pair<List<ProviderLibraryFolder>, List<ProviderLibraryItem>> {
        val folderItems = linkedMapOf<String, MutableList<ProviderLibraryItem>>()

        addTraktFolder(
            folderItems,
            "continue-watching",
            fetchTraktContinueWatching(System.currentTimeMillis()).map {
                ProviderLibraryItem(
                    provider = WatchProvider.TRAKT,
                    folderId = "continue-watching",
                    contentId = it.contentId,
                    contentType = it.contentType,
                    title = it.title,
                    season = it.season,
                    episode = it.episode,
                    addedAtEpochMs = it.lastUpdatedEpochMs,
                )
            },
            limitPerFolder,
        )

        addTraktFolder(folderItems, "watched", traktHistoryItems(), limitPerFolder)
        addTraktFolder(folderItems, "watchlist", traktWatchlistItems(), limitPerFolder)
        addTraktFolder(folderItems, "collection", traktCollectionItems(), limitPerFolder)
        addTraktFolder(folderItems, "ratings", traktRatingsItems(), limitPerFolder)

        val folders =
            folderItems.entries
                .filter { it.value.isNotEmpty() }
                .map { (id, values) ->
                    ProviderLibraryFolder(
                        id = id,
                        label = id.replace('-', ' ').replaceFirstChar { it.uppercase() },
                        provider = WatchProvider.TRAKT,
                        itemCount = values.size,
                    )
                }
        return folders to folderItems.values.flatten()
    }

    private fun addTraktFolder(
        bucket: LinkedHashMap<String, MutableList<ProviderLibraryItem>>,
        id: String,
        values: List<ProviderLibraryItem>,
        limit: Int,
    ) {
        if (values.isEmpty()) return
        val capped = values.take(limit.coerceAtLeast(1))
        val folderId = id
        bucket.getOrPut(folderId) { mutableListOf() }.addAll(capped.map { it.copy(folderId = folderId) })
    }

    private suspend fun traktHistoryItems(): List<ProviderLibraryItem> {
        val movies = traktGetArray("/sync/watched/movies") ?: JSONArray()
        val shows = traktGetArray("/sync/watched/shows") ?: JSONArray()
        return parseTraktItemsFromWatched(movies, MetadataLabMediaType.MOVIE, "watched") +
            parseTraktItemsFromWatched(shows, MetadataLabMediaType.SERIES, "watched")
    }

    private suspend fun traktWatchlistItems(): List<ProviderLibraryItem> {
        val movies = traktGetArray("/sync/watchlist/movies") ?: JSONArray()
        val shows = traktGetArray("/sync/watchlist/shows") ?: JSONArray()
        return parseTraktItemsFromList(movies, "movie", MetadataLabMediaType.MOVIE, "watchlist") +
            parseTraktItemsFromList(shows, "show", MetadataLabMediaType.SERIES, "watchlist")
    }

    private suspend fun traktCollectionItems(): List<ProviderLibraryItem> {
        val movies = traktGetArray("/sync/collection/movies") ?: JSONArray()
        val shows = traktGetArray("/sync/collection/shows") ?: JSONArray()
        return parseTraktItemsFromList(movies, "movie", MetadataLabMediaType.MOVIE, "collection") +
            parseTraktItemsFromList(shows, "show", MetadataLabMediaType.SERIES, "collection")
    }

    private suspend fun traktRatingsItems(): List<ProviderLibraryItem> {
        val movies = traktGetArray("/sync/ratings/movies") ?: JSONArray()
        val shows = traktGetArray("/sync/ratings/shows") ?: JSONArray()
        return parseTraktItemsFromList(movies, "movie", MetadataLabMediaType.MOVIE, "ratings") +
            parseTraktItemsFromList(shows, "show", MetadataLabMediaType.SERIES, "ratings")
    }

    private suspend fun fetchTraktRecommendationsMixed(limit: Int): List<ProviderLibraryItem> {
        val movies = traktGetArray("/recommendations/movies?limit=$limit&extended=images") ?: JSONArray()
        val shows = traktGetArray("/recommendations/shows?limit=$limit&extended=images") ?: JSONArray()
        val movieItems = parseTraktRecommendationsArray(movies, MetadataLabMediaType.MOVIE)
        val showItems = parseTraktRecommendationsArray(shows, MetadataLabMediaType.SERIES)

        val merged = mutableListOf<ProviderLibraryItem>()
        val maxSize = maxOf(movieItems.size, showItems.size)
        for (index in 0 until maxSize) {
            movieItems.getOrNull(index)?.let { merged += it }
            if (merged.size >= limit) break
            showItems.getOrNull(index)?.let { merged += it }
            if (merged.size >= limit) break
        }
        return merged.take(limit)
    }

    private fun parseTraktRecommendationsArray(
        array: JSONArray,
        contentType: MetadataLabMediaType,
    ): List<ProviderLibraryItem> {
        return buildList {
            for (index in 0 until array.length()) {
                val node = array.optJSONObject(index) ?: continue
                val media = node.optJSONObject("movie") ?: node.optJSONObject("show") ?: node
                val contentId = normalizedContentIdFromIds(media.optJSONObject("ids"))
                if (contentId.isEmpty()) continue
                val title = media.optString("title").trim().ifEmpty { contentId }
                val rankedAt =
                    parseIsoToEpochMs(node.optString("listed_at"))
                        ?: parseIsoToEpochMs(node.optString("updated_at"))
                        ?: parseIsoToEpochMs(media.optString("listed_at"))
                        ?: parseIsoToEpochMs(media.optString("updated_at"))
                        ?: parseIsoToEpochMs(node.optString("released"))
                        ?: parseIsoToEpochMs(media.optString("released"))
                        ?: (System.currentTimeMillis() - index)

                add(
                    ProviderLibraryItem(
                        provider = WatchProvider.TRAKT,
                        folderId = "for-you",
                        contentId = contentId,
                        contentType = contentType,
                        title = title,
                        addedAtEpochMs = rankedAt,
                    )
                )
            }
        }
    }

    private fun parseTraktItemsFromWatched(
        array: JSONArray,
        contentType: MetadataLabMediaType,
        folderId: String,
    ): List<ProviderLibraryItem> {
        var skippedNoId = 0
        return buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val node = if (contentType == MetadataLabMediaType.MOVIE) obj.optJSONObject("movie") else obj.optJSONObject("show")
                val contentId = normalizedContentIdFromIds(node?.optJSONObject("ids"))
                if (contentId.isEmpty()) {
                    skippedNoId++
                    continue
                }
                val title = node?.optString("title")?.trim().orEmpty().ifBlank { contentId }
                val addedAt = parseIsoToEpochMs(obj.optString("last_watched_at")) ?: System.currentTimeMillis()
                add(
                    ProviderLibraryItem(
                        provider = WatchProvider.TRAKT,
                        folderId = folderId,
                        contentId = contentId,
                        contentType = contentType,
                        title = title,
                        addedAtEpochMs = addedAt,
                    )
                )
            }
        }.also {
            if (skippedNoId > 0) {
                Log.d(TAG, "parseTraktItemsFromWatched($folderId, $contentType): skipped $skippedNoId items with no supported id (imdb/tmdb)")
            }
        }
    }

    private fun parseTraktItemsFromList(
        array: JSONArray,
        key: String,
        contentType: MetadataLabMediaType,
        folderId: String,
    ): List<ProviderLibraryItem> {
        var skippedNoNode = 0
        var skippedNoId = 0
        return buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val node = obj.optJSONObject(key) ?: run {
                    skippedNoNode++
                    continue
                }
                val contentId = normalizedContentIdFromIds(node.optJSONObject("ids"))
                if (contentId.isEmpty()) {
                    skippedNoId++
                    continue
                }
                val title = node.optString("title").trim().ifEmpty { contentId }
                val addedAt =
                    parseIsoToEpochMs(obj.optString("listed_at"))
                        ?: parseIsoToEpochMs(obj.optString("rated_at"))
                        ?: parseIsoToEpochMs(obj.optString("collected_at"))
                        ?: System.currentTimeMillis()
                add(
                    ProviderLibraryItem(
                        provider = WatchProvider.TRAKT,
                        folderId = folderId,
                        contentId = contentId,
                        contentType = contentType,
                        title = title,
                        addedAtEpochMs = addedAt,
                    )
                )
            }
        }.also {
            if (skippedNoNode > 0 || skippedNoId > 0) {
                Log.d(TAG, "parseTraktItemsFromList($folderId, $key): skipped noNode=$skippedNoNode, noId=$skippedNoId out of ${array.length()}")
            }
        }
    }

    private suspend fun resolveTraktId(imdbId: String, tmdbId: Int?, traktType: String): String? {
        val normalizedImdbId = imdbId.trim()
        if (normalizedImdbId.isNotEmpty()) {
            val search = traktSearchGetArray(traktType, "imdb", normalizedImdbId)
            val id = extractTraktIdFromSearch(search, traktType)
            if (id != null) return id
        }

        if (tmdbId != null && tmdbId > 0) {
            val search = traktSearchGetArray(traktType, "tmdb", tmdbId.toString())
            val id = extractTraktIdFromSearch(search, traktType)
            if (id != null) return id
        }
        return null
    }

    private fun extractTraktIdFromSearch(search: JSONArray?, traktType: String): String? {
        if (search == null || search.length() == 0) return null
        val first = search.optJSONObject(0) ?: return null
        val node = first.optJSONObject(traktType) ?: return null
        return node.optJSONObject("ids")?.opt("trakt")?.toString()?.trim()?.ifEmpty { null }
    }

    private suspend fun syncTraktWatchlist(request: NormalizedContentRequest, inWatchlist: Boolean): Boolean {
        val ids = traktIdsForContent(request.contentId, request.remoteImdbId) ?: return false
        val payload = JSONObject()
        if (request.contentType == MetadataLabMediaType.MOVIE) {
            payload.put("movies", JSONArray().put(JSONObject().put("ids", ids)))
        } else {
            payload.put("shows", JSONArray().put(JSONObject().put("ids", ids)))
        }
        val path = if (inWatchlist) "/sync/watchlist" else "/sync/watchlist/remove"
        return traktPost(path, payload)
    }

    private suspend fun syncTraktRating(request: NormalizedContentRequest, rating: Int?): Boolean {
        val ids = traktIdsForContent(request.contentId, request.remoteImdbId) ?: return false
        val payload = JSONObject()
        val item = JSONObject().put("ids", ids)
        if (rating != null) item.put("rating", rating)

        if (request.contentType == MetadataLabMediaType.MOVIE) {
            payload.put("movies", JSONArray().put(item))
        } else {
            payload.put("shows", JSONArray().put(item))
        }

        val path = if (rating == null) "/sync/ratings/remove" else "/sync/ratings"
        return traktPost(path, payload)
    }

    private fun traktIdsForContent(contentId: String, remoteImdbId: String?): JSONObject? {
        val ids = JSONObject()
        if (!remoteImdbId.isNullOrBlank()) {
            ids.put("imdb", remoteImdbId)
        }

        val tmdbId =
            Regex("""\\btmdb:(?:movie:|show:)?(\\d+)""", RegexOption.IGNORE_CASE)
                .find(contentId)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: 0
        if (tmdbId > 0) {
            ids.put("tmdb", tmdbId)
        }

        return if (ids.length() > 0) ids else null
    }

    private fun normalizeContentRequest(request: WatchHistoryRequest): NormalizedContentRequest {
        val normalizedId = normalizeNuvioMediaId(request.contentId)
        val contentId = normalizedId.contentId.trim()
        require(contentId.isNotEmpty()) { "Content ID is required" }

        val requestRemoteImdbId = request.remoteImdbId?.trim()
        val remoteImdbId =
            when {
                contentId.startsWith("tt", ignoreCase = true) -> contentId.lowercase()
                requestRemoteImdbId?.startsWith("tt", ignoreCase = true) == true -> requestRemoteImdbId.lowercase()
                else -> null
            }

        val title = request.title?.trim()?.takeIf { value -> value.isNotEmpty() } ?: contentId
        return NormalizedContentRequest(
            contentId = contentId,
            contentType = request.contentType,
            title = title,
            remoteImdbId = remoteImdbId,
        )
    }

    private fun traktIdsForRequest(request: NormalizedWatchRequest): JSONObject? {
        val ids = JSONObject()
        request.remoteImdbId?.trim()?.takeIf { it.isNotBlank() }?.let { ids.put("imdb", it) }

        val tmdbId =
            runCatching {
                Regex("""\btmdb:(?:movie:|show:)?(\d+)""", RegexOption.IGNORE_CASE)
                    .find(request.contentId)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
            }.getOrNull()
                ?.takeIf { it > 0 }
        if (tmdbId != null) {
            ids.put("tmdb", tmdbId)
        }

        return if (ids.length() == 0) null else ids
    }

    private suspend fun syncTraktMark(request: NormalizedWatchRequest): Boolean {
        val ids = traktIdsForRequest(request) ?: return false
        if (traktClientId.isBlank()) return false

        val watchedAtIso = Instant.ofEpochMilli(request.watchedAtEpochMs).toString()
        val body =
            if (request.contentType == MetadataLabMediaType.MOVIE) {
                JSONObject().put(
                    "movies",
                    JSONArray().put(
                        JSONObject()
                            .put("watched_at", watchedAtIso)
                            .put("ids", ids)
                    )
                )
            } else {
                JSONObject().put(
                    "shows",
                    JSONArray().put(
                        JSONObject()
                            .put("ids", ids)
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

    private suspend fun syncTraktUnmark(request: NormalizedWatchRequest): Boolean {
        val ids = traktIdsForRequest(request) ?: return false
        if (traktClientId.isBlank()) return false

        val body =
            if (request.contentType == MetadataLabMediaType.MOVIE) {
                JSONObject().put(
                    "movies",
                    JSONArray().put(
                        JSONObject().put("ids", ids)
                    )
                )
            } else {
                JSONObject().put(
                    "shows",
                    JSONArray().put(
                        JSONObject()
                            .put("ids", ids)
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

    private suspend fun syncTraktRemovePlayback(playbackId: String): Boolean {
        val id = playbackId.trim()
        if (id.isEmpty()) return false
        return traktApi.delete("/sync/playback/$id")
    }

    private suspend fun traktPost(path: String, payload: JSONObject): Boolean {
        return traktApi.post(path = path, payload = payload)
    }

    private suspend fun traktSearchGetArray(traktType: String, idType: String, id: String): JSONArray? {
        val safeType = if (traktType == "movie") "movie" else "show"
        return traktApi.searchGetArray(traktType = safeType, idType = idType, id = id)
    }

    private suspend fun traktGetArray(path: String): JSONArray? {
        return traktApi.getArray(path)
    }

    private suspend fun traktGetObject(path: String): JSONObject? {
        return traktApi.getObject(path)
    }

    private fun parseIsoToEpochMs(value: String?): Long? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching { Instant.parse(text).toEpochMilli() }
            .recoverCatching {
                val normalized =
                    if (text.contains(".")) {
                        text.substringBefore('.') + "Z"
                    } else {
                        text
                    }
                Instant.parse(normalized).toEpochMilli()
            }
            .getOrNull()
    }

    private fun normalizedImdbIdForContent(raw: String): String {
        val cleaned = raw.trim().lowercase(Locale.US)
        if (cleaned.isEmpty()) return ""
        return if (cleaned.startsWith("tt")) cleaned else "tt$cleaned"
    }

    private fun normalizedContentIdFromIds(ids: JSONObject?): String {
        val imdbId = normalizedImdbIdForContent(ids?.optString("imdb")?.trim().orEmpty())
        if (imdbId.isNotEmpty()) return imdbId

        val tmdbAny = ids?.opt("tmdb")
        val tmdbId =
            when (tmdbAny) {
                is Number -> tmdbAny.toInt()
                is String -> tmdbAny.toIntOrNull() ?: 0
                else -> 0
            }
        if (tmdbId > 0) return "tmdb:$tmdbId"

        return ""
    }

    private companion object {
        private const val TAG = "TraktWatchHistory"
    }
}

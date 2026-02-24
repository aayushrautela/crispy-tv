package com.crispy.tv.watchhistory.simkl

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
import java.time.Instant
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal class SimklWatchHistoryProvider(
    private val simklApi: SimklApi,
    private val sessionStore: ProviderSessionStore,
    private val simklClientId: String,
) : WatchHistoryProvider {
    override val source: WatchProvider = WatchProvider.SIMKL

    override fun hasClientId(): Boolean {
        return simklClientId.isNotBlank()
    }

    override fun hasAccessToken(): Boolean {
        return sessionStore.simklAccessToken().isNotEmpty()
    }

    override suspend fun markWatched(request: NormalizedWatchRequest): Boolean {
        return syncSimklMark(request)
    }

    override suspend fun unmarkWatched(request: NormalizedWatchRequest): Boolean {
        return syncSimklUnmark(request)
    }

    override suspend fun setInWatchlist(request: WatchHistoryRequest, inWatchlist: Boolean): Boolean {
        return syncSimklWatchlist(normalizeContentRequest(request), inWatchlist)
    }

    override suspend fun setRating(request: WatchHistoryRequest, rating: Int?): Boolean {
        return syncSimklRating(normalizeContentRequest(request), rating)
    }

    override suspend fun removeFromPlayback(playbackId: String): Boolean {
        return false
    }

    override suspend fun listContinueWatching(nowMs: Long): List<ContinueWatchingEntry> {
        return fetchSimklContinueWatching(nowMs)
    }

    override suspend fun listProviderLibrary(limitPerFolder: Int): Pair<List<ProviderLibraryFolder>, List<ProviderLibraryItem>> {
        return fetchSimklLibrary(limitPerFolder)
    }

    override suspend fun listRecommendations(limit: Int): List<ProviderLibraryItem> {
        return fetchSimklRecommendationsMixed(limit)
    }

    /**
     * Resolves a Simkl ids JSON object to an IMDb-based content ID.
     * Fast path: extracts IMDb directly if present.
     * Slow path: when only TMDB ID is available, uses Simkl /search/id
     * endpoint to look up the IMDb ID (matching Nuvio: IMDb is canonical).
     * Falls back to tmdb:X only if Simkl search also fails.
     */
    private suspend fun resolveImdbIdFromSimklIds(ids: JSONObject?): String {
        val directImdb = normalizedImdbIdForContent(ids?.optString("imdb")?.trim().orEmpty())
        if (directImdb.isNotEmpty()) return directImdb

        val tmdbId = extractTmdbIdFromIds(ids)
        if (tmdbId > 0) {
            val searchResult = simklGetAny("/search/id?tmdb=$tmdbId")
            val searchArray = searchResult.toJsonArrayOrNull()
            if (searchArray != null && searchArray.length() > 0) {
                val first = searchArray.optJSONObject(0)
                val resolvedIds = first?.optJSONObject("ids")
                val resolvedImdb = normalizedImdbIdForContent(
                    resolvedIds?.optString("imdb")?.trim().orEmpty()
                )
                if (resolvedImdb.isNotEmpty()) return resolvedImdb
            }
            return "tmdb:$tmdbId"
        }

        return ""
    }

    private fun extractTmdbIdFromIds(ids: JSONObject?): Int {
        val tmdbAny = ids?.opt("tmdb")
        return when (tmdbAny) {
            is Number -> tmdbAny.toInt()
            is String -> tmdbAny.toIntOrNull() ?: 0
            else -> 0
        }
    }

    private suspend fun fetchSimklContinueWatching(nowMs: Long): List<ContinueWatchingEntry> {
        val payload = simklGetAny("/sync/playback") ?: throw IllegalStateException("Simkl /sync/playback returned null")
        val array = payload.toJsonArrayOrNull() ?: throw IllegalStateException("Simkl /sync/playback returned non-array")

        val staleCutoff = nowMs - STALE_PLAYBACK_WINDOW_MS

        // Cache IMDb resolutions within this CW fetch to avoid repeated Simkl search calls.
        val imdbResolutionCache = mutableMapOf<String, String>()

        suspend fun resolveAndCacheImdb(ids: JSONObject?): String {
            val cacheKey = ids?.toString().orEmpty()
            imdbResolutionCache[cacheKey]?.let { return it }
            val resolved = resolveImdbIdFromSimklIds(ids)
            if (resolved.isNotEmpty()) imdbResolutionCache[cacheKey] = resolved
            return resolved
        }

        return buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val type = obj.optString("type").trim().lowercase(Locale.US)
                val progress = obj.optDouble("progress", -1.0)
                if (progress < 0) continue

                val pausedAt = parseIsoToEpochMs(obj.optString("paused_at")) ?: nowMs

                // Stale window: skip items older than the cutoff
                if (pausedAt < staleCutoff) continue

                // Min progress: skip items barely started
                if (progress < CONTINUE_WATCHING_MIN_PROGRESS_PERCENT) continue

                if (type == "movie") {
                    // Completed movies don't belong in continue watching
                    if (progress >= CONTINUE_WATCHING_COMPLETION_PERCENT) continue

                    val movie = obj.optJSONObject("movie") ?: continue
                    val ids = movie.optJSONObject("ids")
                    val contentId = resolveAndCacheImdb(ids)
                    if (contentId.isEmpty()) continue
                    val title = movie.optString("title").trim().ifEmpty { contentId }
                    add(
                        ContinueWatchingEntry(
                            contentId = contentId,
                            contentType = MetadataLabMediaType.MOVIE,
                            title = title,
                            season = null,
                            episode = null,
                            progressPercent = progress,
                            lastUpdatedEpochMs = pausedAt,
                            provider = WatchProvider.SIMKL,
                            providerPlaybackId = obj.opt("id")?.toString()?.trim()?.ifEmpty { null },
                        )
                    )
                    continue
                }

                val show = obj.optJSONObject("show")
                val episode = obj.optJSONObject("episode")
                if (show == null && episode == null) continue

                val ids = show?.optJSONObject("ids") ?: episode?.optJSONObject("show")
                val contentId = resolveAndCacheImdb(ids)
                if (contentId.isEmpty()) continue

                val showTitle = show?.optString("title")?.trim().orEmpty().ifBlank { contentId }
                val season = episode?.optInt("season", 0)?.takeIf { it > 0 }
                val number =
                    episode?.optInt("episode", 0)?.takeIf { it > 0 }
                        ?: episode?.optInt("number", 0)?.takeIf { it > 0 }
                val episodeTitle = episode?.optString("title")?.trim().orEmpty()
                val title = if (episodeTitle.isBlank()) showTitle else "$showTitle - $episodeTitle"

                if (progress >= CONTINUE_WATCHING_COMPLETION_PERCENT) {
                    // Completed episode — Simkl has no next-episode API.
                    // Keep at 84.9% so the show stays in continue watching
                    // (matches Nuvio's fallback behavior for completed episodes).
                    add(
                        ContinueWatchingEntry(
                            contentId = contentId,
                            contentType = MetadataLabMediaType.SERIES,
                            title = title,
                            season = season,
                            episode = number,
                            progressPercent = CONTINUE_WATCHING_COMPLETION_PERCENT - 0.1,
                            lastUpdatedEpochMs = pausedAt,
                            provider = WatchProvider.SIMKL,
                            providerPlaybackId = obj.opt("id")?.toString()?.trim()?.ifEmpty { null },
                        )
                    )
                    continue
                }

                add(
                    ContinueWatchingEntry(
                        contentId = contentId,
                        contentType = MetadataLabMediaType.SERIES,
                        title = title,
                        season = season,
                        episode = number,
                        progressPercent = progress,
                        lastUpdatedEpochMs = pausedAt,
                        provider = WatchProvider.SIMKL,
                        providerPlaybackId = obj.opt("id")?.toString()?.trim()?.ifEmpty { null },
                    )
                )
            }
        }
    }

    private suspend fun fetchSimklLibrary(limitPerFolder: Int): Pair<List<ProviderLibraryFolder>, List<ProviderLibraryItem>> {
        val folderItems = linkedMapOf<String, MutableList<ProviderLibraryItem>>()

        addSimklFolder(
            folderItems,
            "continue-watching",
            fetchSimklContinueWatching(System.currentTimeMillis()).map {
                ProviderLibraryItem(
                    provider = WatchProvider.SIMKL,
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

        val statuses = listOf("watching", "plantowatch", "completed", "onhold", "dropped")
        val types = listOf(
            "shows" to MetadataLabMediaType.SERIES,
            "movies" to MetadataLabMediaType.MOVIE,
            "anime" to MetadataLabMediaType.SERIES,
        )

        for (status in statuses) {
            for ((type, contentType) in types) {
                val folderId = "$status-$type"
                val endpoint = "/sync/all-items/$type/$status"
                val items = parseSimklListItems(endpoint, folderId, contentType)
                addSimklFolder(folderItems, folderId, items, limitPerFolder)
            }
        }

        addSimklFolder(folderItems, "ratings", parseSimklRatingsItems(), limitPerFolder)

        val folders =
            folderItems.entries
                .filter { it.value.isNotEmpty() }
                .map { (id, values) ->
                    ProviderLibraryFolder(
                        id = id,
                        label = id.replace('-', ' ').replaceFirstChar { it.uppercase() },
                        provider = WatchProvider.SIMKL,
                        itemCount = values.size,
                    )
                }
        return folders to folderItems.values.flatten()
    }

    private fun addSimklFolder(
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

    private suspend fun fetchSimklRecommendationsMixed(limit: Int): List<ProviderLibraryItem> {
        val perType = (limit.coerceAtLeast(1) + 1) / 2
        val movieRefs = fetchSimklRandomRefs(type = "movie", limit = perType)
        val showRefs = fetchSimklRandomRefs(type = "tv", limit = perType)

        val movieItems = movieRefs.mapIndexed { index, ref ->
            resolveSimklRecommendationRef(ref = ref, contentType = MetadataLabMediaType.MOVIE, rank = index)
        }
        val showItems = showRefs.mapIndexed { index, ref ->
            resolveSimklRecommendationRef(ref = ref, contentType = MetadataLabMediaType.SERIES, rank = index)
        }

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

    private suspend fun fetchSimklRandomRefs(type: String, limit: Int): List<SimklRandomRef> {
        val payload = simklGetAny("/search/random/?service=simkl&type=$type&limit=${limit.coerceAtLeast(1)}") ?: return emptyList()
        return extractSimklRandomRefs(payload = payload, expectedType = type)
            .distinctBy { it.id }
            .take(limit.coerceAtLeast(1))
    }

    private suspend fun resolveSimklRecommendationRef(
        ref: SimklRandomRef,
        contentType: MetadataLabMediaType,
        rank: Int,
    ): ProviderLibraryItem {
        val endpoint =
            when (ref.type) {
                "movie", "movies" -> "/movies/${ref.id}?extended=full"
                "anime" -> "/anime/${ref.id}?extended=full"
                else -> "/tv/${ref.id}?extended=full"
            }
        val details = simklGetAny(endpoint).toJsonObjectOrNull()

        val ids = details?.optJSONObject("ids")
        val resolvedContentId = normalizedContentIdFromIds(ids)
        val contentId = resolvedContentId.ifEmpty { "simkl:${ref.id}" }
        val title = details?.optString("title")?.trim().orEmpty().ifBlank { ref.title }
        val addedAt =
            parseIsoToEpochMs(details?.optString("released"))
                ?: parseIsoToEpochMs(details?.optString("release_date"))
                ?: (System.currentTimeMillis() - rank)

        return ProviderLibraryItem(
            provider = WatchProvider.SIMKL,
            folderId = "for-you",
            contentId = contentId,
            contentType = contentType,
            title = title,
            addedAtEpochMs = addedAt,
        )
    }

    private fun extractSimklRandomRefs(payload: Any, expectedType: String): List<SimklRandomRef> {
        val values = mutableListOf<SimklRandomRef>()

        fun fromJsonObject(obj: JSONObject): SimklRandomRef? {
            val idFromField = obj.optInt("id", 0).takeIf { it > 0 }
            val url = obj.optString("url").trim()
            val parsedFromUrl = parseSimklRefFromUrl(url)
            val id = idFromField ?: parsedFromUrl?.id ?: return null
            val type = parsedFromUrl?.type ?: expectedType
            val title = parsedFromUrl?.title ?: "Simkl #$id"
            return SimklRandomRef(id = id, type = type, title = title)
        }

        when (payload) {
            is JSONObject -> fromJsonObject(payload)?.let(values::add)
            is JSONArray -> {
                for (index in 0 until payload.length()) {
                    val node = payload.opt(index)
                    when (node) {
                        is JSONObject -> fromJsonObject(node)?.let(values::add)
                        is JSONArray -> {
                            for (inner in 0 until node.length()) {
                                val entry = node.optJSONObject(inner) ?: continue
                                fromJsonObject(entry)?.let(values::add)
                            }
                        }
                    }
                }
            }
        }

        return values
    }

    private fun parseSimklRefFromUrl(rawUrl: String): SimklRandomRef? {
        val pattern = Regex("""/([a-zA-Z]+)/([0-9]+)(?:/([a-zA-Z0-9\-]+))?""")
        val match = pattern.find(rawUrl) ?: return null
        val type = match.groupValues.getOrNull(1)?.trim()?.lowercase(Locale.US).orEmpty()
        val id = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        val titleSlug = match.groupValues.getOrNull(3)?.trim().orEmpty()
        val title = titleSlug.replace('-', ' ').trim().ifEmpty { "Simkl #$id" }
        return SimklRandomRef(id = id, type = type, title = title)
    }

    private data class SimklRandomRef(
        val id: Int,
        val type: String,
        val title: String,
    )

    private suspend fun parseSimklListItems(
        endpoint: String,
        folderId: String,
        contentType: MetadataLabMediaType,
    ): List<ProviderLibraryItem> {
        val payload = simklGetAny(endpoint) ?: return emptyList()
        val array = payload.toJsonArrayOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val ids = item.optJSONObject("ids")
                    ?: item.optJSONObject("movie")?.optJSONObject("ids")
                    ?: item.optJSONObject("show")?.optJSONObject("ids")
                val contentId = normalizedContentIdFromIds(ids)
                if (contentId.isEmpty()) continue

                val title = item.optString("title").trim().ifEmpty {
                    item.optJSONObject("movie")?.optString("title")?.trim().orEmpty().ifBlank {
                        item.optJSONObject("show")?.optString("title")?.trim().orEmpty().ifBlank { contentId }
                    }
                }
                val addedAt =
                    parseIsoToEpochMs(item.optString("last_watched_at"))
                        ?: parseIsoToEpochMs(item.optString("added_to_watchlist_at"))
                        ?: System.currentTimeMillis()
                add(
                    ProviderLibraryItem(
                        provider = WatchProvider.SIMKL,
                        folderId = folderId,
                        contentId = contentId,
                        contentType = contentType,
                        title = title,
                        addedAtEpochMs = addedAt,
                    )
                )
            }
        }
    }

    private suspend fun parseSimklRatingsItems(): List<ProviderLibraryItem> {
        val payload = simklGetAny("/sync/ratings") ?: return emptyList()
        val objectPayload = payload.toJsonObjectOrNull() ?: return emptyList()
        val items = mutableListOf<ProviderLibraryItem>()
        listOf(
            "movies" to MetadataLabMediaType.MOVIE,
            "shows" to MetadataLabMediaType.SERIES,
            "anime" to MetadataLabMediaType.SERIES,
        ).forEach { (key, contentType) ->
            val array = objectPayload.optJSONArray(key) ?: return@forEach
            items += parseSimklRatingsArray(array, contentType)
        }
        return items
    }

    private fun parseSimklRatingsArray(array: JSONArray, contentType: MetadataLabMediaType): List<ProviderLibraryItem> {
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val ids = item.optJSONObject("ids") ?: continue
                val contentId = normalizedContentIdFromIds(ids)
                if (contentId.isEmpty()) continue
                val title = item.optString("title").trim().ifEmpty { contentId }
                val ratedAt = parseIsoToEpochMs(item.optString("rated_at")) ?: System.currentTimeMillis()
                add(
                    ProviderLibraryItem(
                        provider = WatchProvider.SIMKL,
                        folderId = "ratings",
                        contentId = contentId,
                        contentType = contentType,
                        title = title,
                        addedAtEpochMs = ratedAt,
                    )
                )
            }
        }
    }

    private suspend fun syncSimklWatchlist(request: NormalizedContentRequest, inWatchlist: Boolean): Boolean {
        val imdbId = request.remoteImdbId ?: return false
        if (simklClientId.isBlank()) return false
        if (sessionStore.simklAccessToken().isEmpty()) return false

        val key = if (request.contentType == MetadataLabMediaType.MOVIE) "movies" else "shows"
        val payload = JSONObject()
        val item = JSONObject().put("ids", JSONObject().put("imdb", imdbId))
        if (inWatchlist) {
            item.put("to", "plantowatch")
            payload.put(key, JSONArray().put(item))
            return simklPost("/sync/add-to-list", payload)
        }

        payload.put(key, JSONArray().put(item))
        return simklPost("/sync/remove-from-list", payload)
    }

    private suspend fun syncSimklRating(request: NormalizedContentRequest, rating: Int?): Boolean {
        val value = rating ?: return false
        val imdbId = request.remoteImdbId ?: return false
        if (simklClientId.isBlank()) return false
        if (sessionStore.simklAccessToken().isEmpty()) return false

        val key = if (request.contentType == MetadataLabMediaType.MOVIE) "movies" else "shows"
        val payload = JSONObject()
        val item =
            JSONObject()
                .put("ids", JSONObject().put("imdb", imdbId))
                .put("rating", value.coerceIn(1, 10))
        payload.put(key, JSONArray().put(item))
        return simklPost("/sync/ratings", payload)
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

    private suspend fun syncSimklMark(request: NormalizedWatchRequest): Boolean {
        val token = sessionStore.simklAccessToken()
        val imdbId = request.remoteImdbId
        if (token.isEmpty() || simklClientId.isEmpty() || imdbId == null) return false

        val watchedAtIso = Instant.ofEpochMilli(request.watchedAtEpochMs).toString()
        val body =
            if (request.contentType == MetadataLabMediaType.MOVIE) {
                JSONObject().put(
                    "movies",
                    JSONArray().put(
                        JSONObject()
                            .put("ids", JSONObject().put("imdb", imdbId))
                            .put("watched_at", watchedAtIso)
                    )
                )
            } else {
                JSONObject().put(
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

    private suspend fun syncSimklUnmark(request: NormalizedWatchRequest): Boolean {
        val token = sessionStore.simklAccessToken()
        val imdbId = request.remoteImdbId
        if (token.isEmpty() || simklClientId.isEmpty() || imdbId == null) return false

        val body =
            if (request.contentType == MetadataLabMediaType.MOVIE) {
                JSONObject().put(
                    "movies",
                    JSONArray().put(
                        JSONObject().put("ids", JSONObject().put("imdb", imdbId))
                    )
                )
            } else {
                JSONObject().put(
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

    private suspend fun simklPost(path: String, payload: JSONObject): Boolean {
        val token = sessionStore.simklAccessToken()
        if (token.isBlank()) return false
        return simklApi.post(path = path, accessToken = token, payload = payload)
    }

    private suspend fun simklGetAny(path: String): Any? {
        val token = sessionStore.simklAccessToken()
        if (token.isBlank()) return null
        return simklApi.getAny(path = path, accessToken = token)
    }

    private fun parseIsoToEpochMs(value: String?): Long? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching { Instant.parse(text).toEpochMilli() }
            .recoverCatching {
                val normalized = if (text.contains('.')) text.substringBefore('.') + "Z" else text
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

    private fun Any?.toJsonArrayOrNull(): JSONArray? {
        return this as? JSONArray
    }

    private fun Any?.toJsonObjectOrNull(): JSONObject? {
        return this as? JSONObject
    }
}

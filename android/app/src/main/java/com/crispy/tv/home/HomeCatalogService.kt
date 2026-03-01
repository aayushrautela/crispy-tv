package com.crispy.tv.home

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import com.crispy.tv.accounts.ActiveProfileStore
import com.crispy.tv.accounts.SupabaseAccountClient
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.catalog.CatalogPageResult
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.catalog.DiscoverCatalogRef
import com.crispy.tv.domain.catalog.CatalogFilter
import com.crispy.tv.metadata.tmdb.TmdbApi
import com.crispy.tv.metadata.tmdb.TmdbEnrichmentRepository
import com.crispy.tv.metadata.tmdb.TmdbEnrichmentRepositoryProvider
import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.ContinueWatchingEntry as ProviderContinueWatchingEntry
import com.crispy.tv.player.WatchHistoryEntry
import com.crispy.tv.player.WatchProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

@Immutable
data class HomeHeroItem(
    val id: String,
    val title: String,
    val description: String,
    val rating: String?,
    val year: String? = null,
    val genres: List<String> = emptyList(),
    val backdropUrl: String,
    val addonId: String,
    val type: String
)

@Immutable
data class HomeHeroLoadResult(
    val items: List<HomeHeroItem> = emptyList(),
    val statusMessage: String = ""
)

@Immutable
data class ContinueWatchingItem(
    val id: String,
    val contentId: String,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val watchedAtEpochMs: Long,
    val progressPercent: Double,
    val provider: WatchProvider,
    val providerPlaybackId: String?,
    val isUpNextPlaceholder: Boolean = false,
    val backdropUrl: String?,
    val posterUrl: String?,
    val logoUrl: String?,
    val addonId: String?,
    val type: String
)

@Immutable
data class ContinueWatchingLoadResult(
    val items: List<ContinueWatchingItem> = emptyList(),
    val statusMessage: String = ""
)

data class MediaDetails(
    val id: String,
    val imdbId: String?,
    val mediaType: String,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val logoUrl: String?,
    val description: String?,
    val genres: List<String> = emptyList(),
    val year: String?,
    val runtime: String?,
    val certification: String?,
    val rating: String?,
    val cast: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val creators: List<String> = emptyList(),
    val videos: List<MediaVideo> = emptyList(),
    val addonId: String?
)

data class MediaVideo(
    val id: String,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val released: String?,
    val overview: String?,
    val thumbnailUrl: String?
)

class HomeCatalogService(
    context: Context,
    private val httpClient: CrispyHttpClient,
    supabaseUrl: String,
    supabaseAnonKey: String,
    private val tmdbEnrichmentRepository: TmdbEnrichmentRepository =
        TmdbEnrichmentRepositoryProvider.get(context),
) {
    private val appContext = context.applicationContext

    private val supabaseBaseUrl: String = supabaseUrl.trim().trimEnd('/')
    private val supabaseAnonKeyValue: String = supabaseAnonKey.trim()

    private val supabaseAccountClient =
        SupabaseAccountClient(
            appContext = appContext,
            httpClient = httpClient,
            supabaseUrl = supabaseBaseUrl,
            supabaseAnonKey = supabaseAnonKeyValue
        )
    private val activeProfileStore = ActiveProfileStore(appContext)

    private val continueWatchingMetaCache = mutableMapOf<String, CachedContinueWatchingMeta>()
    private val metaResolveSemaphore = Semaphore(6)

    private val recommendationsCacheLock = Any()
    @Volatile
    private var recommendationsCacheProfileId: String? = null
    @Volatile
    private var recommendationsCache: List<SupabaseCatalogList>? = null
    @Volatile
    private var recommendationsCacheTimestampMs: Long = 0

    suspend fun loadHeroItems(limit: Int = 10): HomeHeroLoadResult {
        val targetCount = limit.coerceAtLeast(1)

        val snapshot = loadSupabaseCatalogSnapshot(forceRefresh = false)
        if (snapshot.lists.isEmpty()) {
            return HomeHeroLoadResult(
                items = emptyList(),
                statusMessage = snapshot.statusMessage
            )
        }

        val firstList = snapshot.lists.first()
        val heroItems =
            firstList.items
                .asSequence()
                .mapNotNull { item ->
                    val backdrop = item.backdropUrl ?: item.posterUrl
                    if (backdrop.isNullOrBlank()) return@mapNotNull null
                    HomeHeroItem(
                        id = item.id,
                        title = item.title,
                        description = firstList.title.ifBlank { "Recommended for you." },
                        rating = item.rating,
                        year = item.year,
                        genres = emptyList(),
                        backdropUrl = backdrop,
                        addonId = "supabase",
                        type = item.type
                    )
                }
                .take(targetCount)
                .toList()

        if (heroItems.isEmpty()) {
            return HomeHeroLoadResult(
                items = emptyList(),
                statusMessage = snapshot.statusMessage.ifBlank { "No featured items available." }
            )
        }

        return HomeHeroLoadResult(items = heroItems, statusMessage = "")
    }

    suspend fun loadContinueWatchingItems(
        entries: List<WatchHistoryEntry>,
        limit: Int = 20
    ): ContinueWatchingLoadResult {
        val targetCount = limit.coerceAtLeast(1)
        val dedupedEntries = entries
            .sortedByDescending { it.watchedAtEpochMs }
            .distinctBy { continueWatchingKey(it) }
            .take(targetCount)

        if (dedupedEntries.isEmpty()) {
            return ContinueWatchingLoadResult(statusMessage = "No continue watching items yet.")
        }

        val items = coroutineScope {
            dedupedEntries.map { entry ->
                async(Dispatchers.IO) {
                    metaResolveSemaphore.acquire()
                    try {
                        val mediaType = entry.asCatalogMediaType()
                        val resolvedMeta = resolveContinueWatchingMeta(entry)
                        ContinueWatchingItem(
                            id = continueWatchingKey(entry),
                            contentId = entry.contentId,
                            title = resolvedMeta?.title ?: fallbackContinueWatchingTitle(entry),
                            season = entry.season,
                            episode = entry.episode,
                            watchedAtEpochMs = entry.watchedAtEpochMs,
                            progressPercent = 100.0,
                            provider = WatchProvider.LOCAL,
                            providerPlaybackId = null,
                            isUpNextPlaceholder = false,
                            backdropUrl = resolvedMeta?.backdropUrl,
                            posterUrl = resolvedMeta?.posterUrl,
                            logoUrl = resolvedMeta?.logoUrl,
                            addonId = resolvedMeta?.addonId,
                            type = mediaType
                        )
                    } finally {
                        metaResolveSemaphore.release()
                    }
                }
            }.awaitAll()
        }

        return ContinueWatchingLoadResult(
            items = items,
            statusMessage = ""
        )
    }

    suspend fun loadContinueWatchingItemsFromProvider(
        entries: List<ProviderContinueWatchingEntry>,
        limit: Int = 20
    ): ContinueWatchingLoadResult {
        val targetCount = limit.coerceAtLeast(1)
        val dedupedEntries = entries
            .sortedByDescending { it.lastUpdatedEpochMs }
            .distinctBy { "${it.contentType.name}:${it.contentId}:${it.season ?: -1}:${it.episode ?: -1}" }
            .take(targetCount)

        if (dedupedEntries.isEmpty()) {
            return ContinueWatchingLoadResult(statusMessage = "No continue watching items yet.")
        }

        val items = coroutineScope {
            dedupedEntries.map { entry ->
                async(Dispatchers.IO) {
                    metaResolveSemaphore.acquire()
                    try {
                        val fakeWatchEntry = WatchHistoryEntry(
                            contentId = entry.contentId,
                            contentType = entry.contentType,
                            title = entry.title,
                            season = entry.season,
                            episode = entry.episode,
                            watchedAtEpochMs = entry.lastUpdatedEpochMs
                        )
                        val mediaType = fakeWatchEntry.asCatalogMediaType()
                        val resolvedMeta = resolveContinueWatchingMeta(fakeWatchEntry)
                        ContinueWatchingItem(
                            id = "${entry.provider.name.lowercase(Locale.US)}:${entry.contentType.name.lowercase(Locale.US)}:${entry.contentId}:${entry.season ?: -1}:${entry.episode ?: -1}",
                            contentId = entry.contentId,
                            title = resolvedMeta?.title ?: entry.title,
                            season = entry.season,
                            episode = entry.episode,
                            watchedAtEpochMs = entry.lastUpdatedEpochMs,
                            progressPercent = entry.progressPercent,
                            provider = entry.provider,
                            providerPlaybackId = entry.providerPlaybackId,
                            isUpNextPlaceholder = entry.isUpNextPlaceholder,
                            backdropUrl = resolvedMeta?.backdropUrl,
                            posterUrl = resolvedMeta?.posterUrl,
                            logoUrl = resolvedMeta?.logoUrl,
                            addonId = resolvedMeta?.addonId,
                            type = mediaType
                        )
                    } finally {
                        metaResolveSemaphore.release()
                    }
                }
            }.awaitAll()
        }

        return ContinueWatchingLoadResult(
            items = items,
            statusMessage = ""
        )
    }

    suspend fun listHomeCatalogSections(limit: Int = 15): Pair<List<CatalogSectionRef>, String> {
        val snapshot = loadSupabaseCatalogSnapshot(forceRefresh = false)
        if (snapshot.lists.isEmpty()) {
            return emptyList<CatalogSectionRef>() to snapshot.statusMessage
        }

        val targetCount =
            limit
                .coerceAtLeast(1)
                .coerceAtMost(MAX_SUPABASE_CATALOGS)

        val sections =
            snapshot.lists
                .take(targetCount)
                .map { list ->
                    CatalogSectionRef(
                        title = list.title,
                        catalogId = list.id
                    )
                }

        return sections to ""
    }

    suspend fun listDiscoverCatalogs(
        mediaType: String? = null,
        limit: Int = 50
    ): Pair<List<DiscoverCatalogRef>, String> {
        val normalizedType =
            mediaType
                ?.trim()
                ?.lowercase(Locale.US)
                ?.takeIf { it.isNotBlank() }
        if (normalizedType != null && normalizedType != "movie" && normalizedType != "series") {
            return emptyList<DiscoverCatalogRef>() to "Unsupported media type: $mediaType"
        }

        val snapshot = loadSupabaseCatalogSnapshot(forceRefresh = false)
        if (snapshot.lists.isEmpty()) {
            return emptyList<DiscoverCatalogRef>() to snapshot.statusMessage
        }

        val filteredLists =
            snapshot.lists.filter { list ->
                normalizedType == null || list.mediaTypes.contains(normalizedType)
            }

        if (filteredLists.isEmpty()) {
            val suffix = if (normalizedType == null) "" else " for $normalizedType"
            return emptyList<DiscoverCatalogRef>() to "No discover catalogs found$suffix."
        }

        val targetCount =
            limit
                .coerceAtLeast(1)
                .coerceAtMost(MAX_SUPABASE_CATALOGS)

        val catalogs =
            filteredLists
                .take(targetCount)
                .map { list ->
                    DiscoverCatalogRef(
                        section = CatalogSectionRef(title = list.title, catalogId = list.id),
                        addonName = "Supabase",
                        genres = emptyList()
                    )
                }

        return catalogs to ""
    }

    suspend fun fetchCatalogPage(
        section: CatalogSectionRef,
        page: Int,
        pageSize: Int,
        filters: List<CatalogFilter> = emptyList()
    ): CatalogPageResult {
        val targetPage = page.coerceAtLeast(1)
        val targetSize = pageSize.coerceAtLeast(1)

        val snapshot = loadSupabaseCatalogSnapshot(forceRefresh = false)
        if (snapshot.lists.isEmpty()) {
            return CatalogPageResult(
                items = emptyList(),
                statusMessage = snapshot.statusMessage,
                attemptedUrls = emptyList()
            )
        }

        val list =
            snapshot.lists.firstOrNull { it.id.equals(section.catalogId.trim(), ignoreCase = true) }
                ?: return CatalogPageResult(
                    items = emptyList(),
                    statusMessage = "Catalog not found.",
                    attemptedUrls = emptyList()
                )

        val attempted =
            listOf(
                "supabase:profile_recommendations:${snapshot.profileId.orEmpty()}:${list.id}:page=$targetPage"
            )

        if (targetPage != 1) {
            return CatalogPageResult(
                items = emptyList(),
                statusMessage = "No more items available.",
                attemptedUrls = attempted
            )
        }

        val items = list.items.take(targetSize)
        return CatalogPageResult(
            items = items,
            statusMessage = if (items.isEmpty()) "No catalog items available." else "",
            attemptedUrls = attempted
        )
    }

    private suspend fun loadSupabaseCatalogSnapshot(forceRefresh: Boolean): SupabaseCatalogSnapshot {
        if (!supabaseAccountClient.isConfigured()) {
            return SupabaseCatalogSnapshot(
                profileId = null,
                lists = emptyList(),
                statusMessage = "Supabase is not configured."
            )
        }

        val session =
            runCatching { supabaseAccountClient.ensureValidSession() }.getOrNull()
                ?: return SupabaseCatalogSnapshot(
                    profileId = null,
                    lists = emptyList(),
                    statusMessage = "Sign in to Supabase to load catalogs."
                )

        val profileId = activeProfileStore.getActiveProfileId(session.userId)
        if (profileId.isNullOrBlank()) {
            return SupabaseCatalogSnapshot(
                profileId = null,
                lists = emptyList(),
                statusMessage = "Select a profile to load catalogs."
            )
        }

        val now = System.currentTimeMillis()
        synchronized(recommendationsCacheLock) {
            val cachedLists = recommendationsCache
            val cachedProfileId = recommendationsCacheProfileId
            val cacheAgeMs = now - recommendationsCacheTimestampMs
            if (!forceRefresh && cachedLists != null && cachedProfileId == profileId && cacheAgeMs < SUPABASE_CATALOGS_CACHE_TTL_MS) {
                return SupabaseCatalogSnapshot(
                    profileId = profileId,
                    lists = cachedLists,
                    statusMessage = ""
                )
            }
        }

        val (lists, statusMessage) =
            fetchSupabaseCatalogLists(
                accessToken = session.accessToken,
                profileId = profileId
            )

        synchronized(recommendationsCacheLock) {
            recommendationsCacheProfileId = profileId
            recommendationsCache = lists
            recommendationsCacheTimestampMs = System.currentTimeMillis()
        }

        return SupabaseCatalogSnapshot(
            profileId = profileId,
            lists = lists,
            statusMessage = statusMessage
        )
    }

    private suspend fun fetchSupabaseCatalogLists(
        accessToken: String,
        profileId: String,
    ): Pair<List<SupabaseCatalogList>, String> {
        if (supabaseBaseUrl.isBlank() || supabaseAnonKeyValue.isBlank()) {
            return emptyList<SupabaseCatalogList>() to "Supabase is not configured."
        }

        val url =
            runCatching {
                "$supabaseBaseUrl/rest/v1/profile_recommendations".toHttpUrl().newBuilder()
                    .addQueryParameter("select", "lists")
                    .addQueryParameter("profile_id", "eq.$profileId")
                    .addQueryParameter("order", "generated_at.desc")
                    .addQueryParameter("limit", "1")
                    .build()
            }.getOrElse { error ->
                return emptyList<SupabaseCatalogList>() to (error.message ?: "Invalid Supabase URL.")
            }

        val response =
            runCatching {
                httpClient.get(
                    url = url,
                    headers = supabaseAuthHeaders(accessToken),
                    callTimeoutMs = 12_000L,
                )
            }.getOrElse { error ->
                Log.w(TAG, "Supabase catalogs fetch failed", error)
                return emptyList<SupabaseCatalogList>() to (error.message ?: "Failed to load catalogs.")
            }

        if (response.code !in 200..299) {
            val msg = extractSupabaseErrorMessage(response.body)
            return emptyList<SupabaseCatalogList>() to (msg ?: "Supabase catalogs request failed (HTTP ${response.code}).")
        }

        val body = response.body
        if (body.isBlank()) {
            return emptyList<SupabaseCatalogList>() to "No catalogs available."
        }

        val lists =
            runCatching { parseSupabaseCatalogLists(body) }.getOrElse { error ->
                Log.w(TAG, "Supabase catalogs parse failed", error)
                return emptyList<SupabaseCatalogList>() to "Failed to parse catalogs."
            }

        if (lists.isEmpty()) {
            return emptyList<SupabaseCatalogList>() to "No catalogs available."
        }

        return lists to ""
    }

    private fun supabaseAuthHeaders(accessToken: String): Headers {
        val token = accessToken.trim()
        return Headers.headersOf(
            "apikey",
            supabaseAnonKeyValue,
            "Authorization",
            "Bearer $token",
            "Accept",
            "application/json",
            "Content-Type",
            "application/json",
        )
    }

    private fun extractSupabaseErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return nonBlank(obj.optString("message"))
            ?: nonBlank(obj.optString("msg"))
            ?: nonBlank(obj.optString("error_description"))
            ?: nonBlank(obj.optString("error"))
    }

    private fun parseSupabaseCatalogLists(body: String): List<SupabaseCatalogList> {
        val rows = JSONArray(body)
        val row = rows.optJSONObject(0) ?: return emptyList()
        val lists = row.optJSONArray("lists") ?: return emptyList()

        val parsed = mutableListOf<SupabaseCatalogList>()
        val targetCount = minOf(lists.length(), MAX_SUPABASE_CATALOGS)
        for (index in 0 until targetCount) {
            val listObj = lists.optJSONObject(index) ?: continue
            val list = parseSupabaseCatalogList(listObj) ?: continue
            parsed += list
        }
        return parsed
    }

    private fun parseSupabaseCatalogList(obj: JSONObject): SupabaseCatalogList? {
        val id = nonBlank(obj.optString("name")) ?: return null
        val title = nonBlank(obj.optString("heading")) ?: id
        val results = obj.optJSONArray("results")

        val items =
            if (results == null) {
                emptyList()
            } else {
                buildList {
                    for (i in 0 until results.length()) {
                        val itemObj = results.optJSONObject(i) ?: continue
                        val item = parseSupabaseCatalogItem(itemObj) ?: continue
                        add(item)
                    }
                }

        val mediaTypes = items.map { it.type }.toSet()

        return SupabaseCatalogList(
            id = id,
            title = title,
            items = items,
            mediaTypes = mediaTypes
        )
    }

    private fun parseSupabaseCatalogItem(obj: JSONObject): CatalogItem? {
        val idAny = obj.opt("id")
        val id =
            when (idAny) {
                is Number -> idAny.toLong().takeIf { it > 0 }?.toString()
                is String -> nonBlank(idAny)
                else -> null
            } ?: return null

        val rawMediaType = nonBlank(obj.optString("media_type"))?.lowercase(Locale.US)
        val type = normalizeSupabaseMediaType(rawMediaType) ?: return null

        val title =
            nonBlank(obj.optString("title"))
                ?: nonBlank(obj.optString("name"))
                ?: return null

        val posterPath = nonBlank(obj.optString("poster_path"))
        val backdropPath = nonBlank(obj.optString("backdrop_path"))

        val posterUrl = TmdbApi.imageUrl(posterPath, size = "w500")
        val backdropUrl = TmdbApi.imageUrl(backdropPath, size = "w780")
        val rating = formatVoteAverage(obj.optDoubleOrNull("vote_average"))

        return CatalogItem(
            id = id,
            title = title,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            addonId = "supabase",
            type = type,
            rating = rating,
            year = null,
            genre = null
        )
    }

    private fun normalizeSupabaseMediaType(raw: String?): String? {
        return when (raw?.trim()?.lowercase(Locale.US)) {
            "movie" -> "movie"
            "tv", "series", "show" -> "series"
            else -> null
        }
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        val raw = opt(key) ?: return null
        return when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull()
            else -> null
        }
    }

    private fun formatVoteAverage(value: Double?): String? {
        val v = value ?: return null
        if (!v.isFinite() || v <= 0.0) return null
        val formatted = String.format(Locale.US, "%.1f", v)
        return if (formatted.endsWith(".0")) formatted.dropLast(2) else formatted
    }

    private data class SupabaseCatalogSnapshot(
        val profileId: String?,
        val lists: List<SupabaseCatalogList>,
        val statusMessage: String,
    )

    private data class SupabaseCatalogList(
        val id: String,
        val title: String,
        val items: List<CatalogItem>,
        val mediaTypes: Set<String>,
    )

    private suspend fun resolveContinueWatchingMeta(entry: WatchHistoryEntry): ContinueWatchingMeta? {
        val rawId = entry.contentId.trim()
        if (rawId.isBlank()) {
            return null
        }

        val episodeMatch = EPISODE_SUFFIX_REGEX.matchEntire(rawId)
        val baseId = episodeMatch?.groupValues?.get(1)?.trim().orEmpty().ifBlank { rawId }
        val mediaType = entry.asCatalogMediaType()
        val cacheKey = continueWatchingCacheKey(mediaType = mediaType, contentId = baseId)

        val cached = readCachedContinueWatchingMeta(cacheKey = cacheKey)
        if (cached != null) {
            return cached
        }

        val details =
            runCatching {
                tmdbEnrichmentRepository.loadArtwork(
                    rawId = baseId,
                    mediaTypeHint = entry.contentType,
                )
            }.getOrNull() ?: return null

        val resolved =
            ContinueWatchingMeta(
                title = nonBlank(details.title) ?: nonBlank(entry.title),
                backdropUrl = details.backdropUrl,
                posterUrl = details.posterUrl,
                logoUrl = details.logoUrl,
                addonId = details.addonId ?: "tmdb",
            )
        if (!resolved.hasDisplayData()) {
            return null
        }

        cacheContinueWatchingMeta(cacheKey = cacheKey, value = resolved)
        return resolved
    }

    private fun continueWatchingCacheKey(mediaType: String, contentId: String): String {
        return "${mediaType.lowercase(Locale.US)}:${contentId.lowercase(Locale.US)}"
    }

    private fun readCachedContinueWatchingMeta(cacheKey: String): ContinueWatchingMeta? {
        val cached = continueWatchingMetaCache[cacheKey] ?: return null
        val ageMs = System.currentTimeMillis() - cached.cachedAtEpochMs
        if (ageMs > CONTINUE_WATCHING_META_CACHE_TTL_MS) {
            continueWatchingMetaCache.remove(cacheKey)
            return null
        }
        return cached.meta
    }

    private fun cacheContinueWatchingMeta(cacheKey: String, value: ContinueWatchingMeta) {
        continueWatchingMetaCache[cacheKey] =
            CachedContinueWatchingMeta(
                meta = value,
                cachedAtEpochMs = System.currentTimeMillis()
            )
    }

    private fun fallbackContinueWatchingTitle(entry: WatchHistoryEntry): String {
        val normalizedTitle = nonBlank(entry.title)
        if (normalizedTitle != null) {
            return normalizedTitle
        }
        if (entry.season != null && entry.episode != null) {
            return "${entry.contentId} S${entry.season} E${entry.episode}"
        }
        return entry.contentId
    }

    private fun continueWatchingKey(entry: WatchHistoryEntry): String {
        val seasonPart = entry.season?.toString() ?: "-"
        val episodePart = entry.episode?.toString() ?: "-"
        return "${entry.contentType.name.lowercase(Locale.US)}:${entry.contentId}:$seasonPart:$episodePart"
    }

    private data class ContinueWatchingMeta(
        val title: String?,
        val backdropUrl: String?,
        val posterUrl: String?,
        val logoUrl: String?,
        val addonId: String?
    ) {
        fun hasDisplayData(): Boolean {
            return !title.isNullOrBlank() || !backdropUrl.isNullOrBlank() || !posterUrl.isNullOrBlank() || !logoUrl.isNullOrBlank()
        }
    }

    private data class CachedContinueWatchingMeta(
        val meta: ContinueWatchingMeta,
        val cachedAtEpochMs: Long
    )

    companion object {
        private const val TAG = "HomeCatalogService"
        private val EPISODE_SUFFIX_REGEX = Regex("^(.*):(\\d+):(\\d+)$")

        private const val MAX_SUPABASE_CATALOGS = 6

        private const val CONTINUE_WATCHING_META_CACHE_TTL_MS = 5 * 60 * 1000L
        private const val SUPABASE_CATALOGS_CACHE_TTL_MS = 60_000L
    }
}

private fun nonBlank(value: String?): String? {
    val trimmed = value?.trim()
    return if (trimmed.isNullOrEmpty()) null else trimmed
}

private fun WatchHistoryEntry.asCatalogMediaType(): String {
    return if (contentType == MetadataLabMediaType.SERIES) "series" else "movie"
}

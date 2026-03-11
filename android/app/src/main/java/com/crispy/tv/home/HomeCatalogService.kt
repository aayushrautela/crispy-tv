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
import com.crispy.tv.domain.home.HomeCatalogDiscoverRef
import com.crispy.tv.domain.home.HomeCatalogHeroItem as DomainHomeHeroItem
import com.crispy.tv.domain.home.HomeCatalogHeroResult as DomainHomeHeroResult
import com.crispy.tv.domain.home.HomeCatalogItem as DomainHomeCatalogItem
import com.crispy.tv.domain.home.HomeCatalogList as DomainHomeCatalogList
import com.crispy.tv.domain.home.HomeCatalogPageResult as DomainHomeCatalogPageResult
import com.crispy.tv.domain.home.HomeCatalogPersonalFeedPlan
import com.crispy.tv.domain.home.HomeCatalogSection as DomainHomeCatalogSection
import com.crispy.tv.domain.home.HomeCatalogSnapshot as DomainHomeCatalogSnapshot
import com.crispy.tv.domain.home.HomeCatalogSource
import com.crispy.tv.domain.home.buildCatalogPage as planCatalogPage
import com.crispy.tv.domain.home.buildGlobalHeaderSections
import com.crispy.tv.domain.home.listDiscoverCatalogs as planDiscoverCatalogs
import com.crispy.tv.domain.home.planPersonalHomeFeed
import com.crispy.tv.domain.home.resolveHomeCatalogSource
import com.crispy.tv.metadata.tmdb.TmdbApi
import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.network.CrispyHttpResponse
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
data class HomePersonalFeedLoadResult(
    val heroResult: HomeHeroLoadResult = HomeHeroLoadResult(),
    val sections: List<CatalogSectionRef> = emptyList(),
    val sectionsStatusMessage: String = "",
)

@Immutable
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

@Immutable
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

    private val catalogCacheLock = Any()
    private val personalCatalogCache = SupabaseCatalogCache()
    private val globalCatalogCache = SupabaseCatalogCache()

    suspend fun loadPersonalHomeFeed(
        heroLimit: Int = 10,
        sectionLimit: Int = Int.MAX_VALUE,
    ): HomePersonalFeedLoadResult {
        val snapshot =
            loadSupabaseCatalogSnapshot(
                forceRefresh = false,
                source = HomeCatalogSource.PERSONAL,
            )
        return planPersonalHomeFeed(snapshot.toDomain(), heroLimit = heroLimit, sectionLimit = sectionLimit).toAppModel()
    }

    suspend fun loadGlobalHeaderSections(limit: Int = Int.MAX_VALUE): List<CatalogSectionRef> {
        val snapshot =
            loadSupabaseCatalogSnapshot(
                forceRefresh = false,
                source = HomeCatalogSource.GLOBAL,
            )
        return buildGlobalHeaderSections(snapshot.toDomain(), limit).map { it.toAppModel() }
    }

    suspend fun listDiscoverCatalogs(
        mediaType: String? = null,
        limit: Int = Int.MAX_VALUE
    ): Pair<List<DiscoverCatalogRef>, String> {
        val snapshot =
            loadSupabaseCatalogSnapshot(
                forceRefresh = false,
                source = HomeCatalogSource.PERSONAL
            )
        val (catalogs, statusMessage) = planDiscoverCatalogs(snapshot.toDomain(), mediaType = mediaType, limit = limit)
        return catalogs.map { it.toAppModel() } to statusMessage
    }

    suspend fun fetchCatalogPage(
        section: CatalogSectionRef,
        page: Int,
        pageSize: Int,
        filters: List<CatalogFilter> = emptyList()
    ): CatalogPageResult {
        val targetPage = page.coerceAtLeast(1)
        val targetSize = pageSize.coerceAtLeast(1)

        val catalogSource = resolveHomeCatalogSource(section.catalogId)
        val snapshot =
            loadSupabaseCatalogSnapshot(
                forceRefresh = false,
                source = catalogSource
            )
        return planCatalogPage(
            snapshot = snapshot.toDomain(),
            sectionCatalogId = section.catalogId,
            page = targetPage,
            pageSize = targetSize,
        ).toAppModel()
    }

    private suspend fun loadSupabaseCatalogSnapshot(
        forceRefresh: Boolean,
        source: HomeCatalogSource,
    ): SupabaseCatalogSnapshot {
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

        val cache = catalogCache(source)
        val now = System.currentTimeMillis()
        synchronized(catalogCacheLock) {
            val cachedLists = cache.lists
            val cachedProfileId = cache.profileId
            val cacheAgeMs = now - cache.timestampMs
            if (!forceRefresh && cachedLists != null && cachedProfileId == profileId && cacheAgeMs < SUPABASE_CATALOGS_CACHE_TTL_MS) {
                return SupabaseCatalogSnapshot(
                    profileId = profileId,
                    lists = cachedLists,
                    statusMessage = ""
                )
            }
        }

        val (lists, statusMessage) =
            when (source) {
                HomeCatalogSource.PERSONAL -> {
                    fetchProfileRecommendationsCatalogLists(
                        accessToken = session.accessToken,
                        profileId = profileId
                    )
                }

                HomeCatalogSource.GLOBAL -> {
                    fetchGlobalCatalogLists(
                        accessToken = session.accessToken,
                        profileId = profileId
                    )
                }
            }

        synchronized(catalogCacheLock) {
            cache.profileId = profileId
            cache.lists = lists
            cache.timestampMs = System.currentTimeMillis()
        }

        return SupabaseCatalogSnapshot(
            profileId = profileId,
            lists = lists,
            statusMessage = statusMessage
        )
    }

    private suspend fun fetchProfileRecommendationsCatalogLists(
        accessToken: String,
        profileId: String,
    ): Pair<List<SupabaseCatalogList>, String> {
        val url =
            runCatching {
                "$supabaseBaseUrl/rest/v1/profile_recommendations"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("select", "lists")
                    .addQueryParameter("profile_id", "eq.${profileId.trim()}")
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
                Log.w(TAG, "Supabase profile recommendations fetch failed", error)
                return emptyList<SupabaseCatalogList>() to (error.message ?: "Failed to load catalogs.")
            }

        return parseSupabaseCatalogResponse(response)
    }

    private suspend fun fetchGlobalCatalogLists(
        accessToken: String,
        profileId: String,
    ): Pair<List<SupabaseCatalogList>, String> {
        val url =
            runCatching {
                "$supabaseBaseUrl/rest/v1/rpc/get_global_lists_feed".toHttpUrl()
            }.getOrElse { error ->
                return emptyList<SupabaseCatalogList>() to (error.message ?: "Invalid Supabase URL.")
            }

        val payload =
            JSONObject()
                .put("p_profile_id", profileId.trim())
                .put("p_limit", SUPABASE_GLOBAL_LISTS_LIMIT)
                .toString()

        val response =
            runCatching {
                httpClient.postJson(
                    url = url,
                    jsonBody = payload,
                    headers = supabaseAuthHeaders(accessToken),
                    callTimeoutMs = 12_000L,
                )
            }.getOrElse { error ->
                Log.w(TAG, "Supabase global catalogs fetch failed", error)
                return emptyList<SupabaseCatalogList>() to (error.message ?: "Failed to load catalogs.")
            }

        return parseSupabaseCatalogResponse(response)
    }

    private fun parseSupabaseCatalogResponse(
        response: CrispyHttpResponse,
    ): Pair<List<SupabaseCatalogList>, String> {
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
        val trimmed = body.trim()
        if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)) {
            return emptyList()
        }

        val lists =
            when {
                trimmed.startsWith("[") -> {
                    val arr = JSONArray(trimmed)
                    val nested =
                        arr.optJSONObject(0)?.let { first ->
                            first.optJSONArray("lists")
                                ?: first.optJSONArray("get_global_lists_feed")
                                ?: first.optJSONArray("feed")
                        }
                    nested ?: arr
                }

                trimmed.startsWith("{") -> {
                    val obj = JSONObject(trimmed)
                    obj.optJSONArray("lists")
                        ?: obj.optJSONArray("get_global_lists_feed")
                        ?: obj.optJSONArray("feed")
                        ?: JSONArray().apply { put(obj) }
                }

                else -> return emptyList()
            }

        val parsed = mutableListOf<SupabaseCatalogList>()
        for (index in 0 until lists.length()) {
            val listObj = lists.optJSONObject(index) ?: continue
            val list = parseSupabaseCatalogList(listObj) ?: continue
            parsed += list
        }
        return parsed
    }

    private fun parseSupabaseCatalogList(obj: JSONObject): SupabaseCatalogList? {
        val kind = nonBlank(obj.optString("kind"))
        val id =
            nonBlank(obj.optString("id"))
                ?: nonBlank(obj.optString("name"))
                ?: kind
                ?: return null
        val title =
            nonBlank(obj.optString("title"))
                ?: nonBlank(obj.optString("name"))
                ?: nonBlank(obj.optString("heading"))
                ?: id
        val subtitle = nonBlank(obj.optString("subtitle"))
        val heading = nonBlank(obj.optString("heading"))
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
            }

        val mediaTypes = items.map { it.type }.toSet()

        return SupabaseCatalogList(
            id = id,
            kind = kind,
            title = title,
            subtitle = subtitle,
            heading = heading,
            items = items,
            mediaTypes = mediaTypes
        )
    }

    private fun parseSupabaseCatalogItem(obj: JSONObject): CatalogItem? {
        val idAny = obj.opt("id")
        val rawId =
            when (idAny) {
                is Number -> idAny.toLong().takeIf { it > 0 }?.toString()
                is String -> nonBlank(idAny)
                else -> null
            } ?: return null

        val rawMediaType = nonBlank(obj.optString("media_type"))?.lowercase(Locale.US)
        val type = normalizeSupabaseMediaType(rawMediaType) ?: return null

        val id = normalizeSupabaseTmdbId(rawId) ?: return null

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
            addonId = "tmdb",
            type = type,
            rating = rating,
            year = null,
            genre = null,
            description = nonBlank(obj.optString("reason"))
        )
    }

    private fun normalizeSupabaseTmdbId(rawId: String): String? {
        val trimmed = rawId.trim()
        if (trimmed.isBlank()) return null

        return when {
            trimmed.startsWith("tmdb:", ignoreCase = true) -> "tmdb:" + trimmed.substring(5)
            trimmed.all { it.isDigit() } -> "tmdb:$trimmed"
            else -> null
        }
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

    private fun catalogCache(source: HomeCatalogSource): SupabaseCatalogCache {
        return when (source) {
            HomeCatalogSource.PERSONAL -> personalCatalogCache
            HomeCatalogSource.GLOBAL -> globalCatalogCache
        }
    }

    private data class SupabaseCatalogSnapshot(
        val profileId: String?,
        val lists: List<SupabaseCatalogList>,
        val statusMessage: String,
    )

    private class SupabaseCatalogCache {
        @Volatile
        var profileId: String? = null

        @Volatile
        var lists: List<SupabaseCatalogList>? = null

        @Volatile
        var timestampMs: Long = 0L
    }

    private data class SupabaseCatalogList(
        val id: String,
        val kind: String?,
        val title: String,
        val subtitle: String?,
        val heading: String?,
        val items: List<CatalogItem>,
        val mediaTypes: Set<String>,
    )

    private fun SupabaseCatalogSnapshot.toDomain(): DomainHomeCatalogSnapshot {
        return DomainHomeCatalogSnapshot(
            profileId = profileId,
            lists = lists.map { it.toDomain() },
            statusMessage = statusMessage,
        )
    }

    private fun SupabaseCatalogList.toDomain(): DomainHomeCatalogList {
        return DomainHomeCatalogList(
            id = id,
            kind = kind,
            title = title,
            subtitle = subtitle,
            heading = heading,
            items = items.map { it.toDomain() },
            mediaTypes = mediaTypes,
        )
    }

    companion object {
        private const val TAG = "HomeCatalogService"
        private const val SUPABASE_CATALOGS_CACHE_TTL_MS = 60_000L
        private const val SUPABASE_GLOBAL_LISTS_LIMIT = 50
    }
}

private fun CatalogItem.toDomain(): DomainHomeCatalogItem {
    return DomainHomeCatalogItem(
        id = id,
        title = title,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        addonId = addonId,
        type = type,
        rating = rating,
        year = year,
        description = description,
    )
}

private fun HomeCatalogPersonalFeedPlan.toAppModel(): HomePersonalFeedLoadResult {
    return HomePersonalFeedLoadResult(
        heroResult = heroResult.toAppModel(),
        sections = sections.map { it.toAppModel() },
        sectionsStatusMessage = sectionsStatusMessage,
    )
}

private fun DomainHomeHeroResult.toAppModel(): HomeHeroLoadResult {
    return HomeHeroLoadResult(
        items = items.map { it.toAppModel() },
        statusMessage = statusMessage,
    )
}

private fun DomainHomeHeroItem.toAppModel(): HomeHeroItem {
    return HomeHeroItem(
        id = id,
        title = title,
        description = description,
        rating = rating,
        year = year,
        genres = genres,
        backdropUrl = backdropUrl,
        addonId = addonId,
        type = type,
    )
}

private fun DomainHomeCatalogSection.toAppModel(): CatalogSectionRef {
    return CatalogSectionRef(
        title = title,
        catalogId = catalogId,
        subtitle = subtitle,
    )
}

private fun HomeCatalogDiscoverRef.toAppModel(): DiscoverCatalogRef {
    return DiscoverCatalogRef(
        section = section.toAppModel(),
        addonName = addonName,
        genres = genres,
    )
}

private fun DomainHomeCatalogPageResult.toAppModel(): CatalogPageResult {
    return CatalogPageResult(
        items = items.map { it.toAppModel() },
        statusMessage = statusMessage,
        attemptedUrls = attemptedUrls,
    )
}

private fun DomainHomeCatalogItem.toAppModel(): CatalogItem {
    return CatalogItem(
        id = id,
        title = title,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        addonId = addonId,
        type = type,
        rating = rating,
        year = year,
        genre = null,
        description = description,
    )
}

private fun nonBlank(value: String?): String? {
    val trimmed = value?.trim()
    return if (trimmed.isNullOrEmpty()) null else trimmed
}

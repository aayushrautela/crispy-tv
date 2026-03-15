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
import com.crispy.tv.domain.home.HomeCatalogFeedPlan
import com.crispy.tv.domain.home.HomeCatalogHeroItem as DomainHomeHeroItem
import com.crispy.tv.domain.home.HomeCatalogHeroResult as DomainHomeHeroResult
import com.crispy.tv.domain.home.HomeCatalogItem as DomainHomeCatalogItem
import com.crispy.tv.domain.home.HomeCatalogList as DomainHomeCatalogList
import com.crispy.tv.domain.home.HomeCatalogPresentation
import com.crispy.tv.domain.home.HomeCatalogSection as DomainHomeCatalogSection
import com.crispy.tv.domain.home.HomeCatalogSnapshot as DomainHomeCatalogSnapshot
import com.crispy.tv.domain.home.HomeCatalogSource
import com.crispy.tv.domain.home.listDiscoverCatalogs as planDiscoverCatalogs
import com.crispy.tv.domain.home.parseHomeCatalogId
import com.crispy.tv.domain.home.planHomeFeed
import com.crispy.tv.metadata.tmdb.TmdbApi
import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.network.CrispyHttpResponse
import com.crispy.tv.ratings.formatRating
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
    val type: String,
)

@Immutable
data class HomeHeroLoadResult(
    val items: List<HomeHeroItem> = emptyList(),
    val statusMessage: String = "",
)

@Immutable
data class HomePrimaryFeedLoadResult(
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
    val addonId: String?,
)

@Immutable
data class MediaVideo(
    val id: String,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val released: String?,
    val overview: String?,
    val thumbnailUrl: String?,
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
            supabaseAnonKey = supabaseAnonKeyValue,
        )
    private val activeProfileStore = ActiveProfileStore(appContext)

    private val catalogCacheLock = Any()
    private val personalCatalogCache = SupabaseCatalogCache()
    private val publicCatalogCache = SupabaseCatalogCache()

    suspend fun loadPrimaryHomeFeed(
        heroLimit: Int = 10,
        sectionLimit: Int = Int.MAX_VALUE,
    ): HomePrimaryFeedLoadResult {
        val snapshot = selectPrimaryFeedSnapshot()
        return planHomeFeed(
            snapshot = snapshot.toDomain(),
            heroLimit = heroLimit,
            sectionLimit = sectionLimit,
        ).toAppModel()
    }

    suspend fun listDiscoverCatalogs(
        mediaType: String? = null,
        limit: Int = Int.MAX_VALUE,
    ): Pair<List<DiscoverCatalogRef>, String> {
        val snapshot =
            loadSupabaseCatalogSnapshot(
                forceRefresh = false,
                source = HomeCatalogSource.PERSONAL,
            )
        val (catalogs, statusMessage) = planDiscoverCatalogs(snapshot.toDomain(), mediaType = mediaType, limit = limit)
        return catalogs.map { it.toAppModel() } to statusMessage
    }

    suspend fun fetchCatalogPage(
        section: CatalogSectionRef,
        page: Int,
        pageSize: Int,
        filters: List<CatalogFilter> = emptyList(),
    ): CatalogPageResult {
        val targetPage = page.coerceAtLeast(1)
        val targetSize = pageSize.coerceAtLeast(1)
        val identifier = parseHomeCatalogId(section.catalogId)
            ?: return CatalogPageResult(statusMessage = "Catalog not found.")

        val targetLimit = (targetPage * targetSize).coerceAtMost(SUPABASE_SECTION_LIMIT_CAP)
        val response =
            when (identifier.source) {
                HomeCatalogSource.PERSONAL -> {
                    val memberAccess = resolveMemberAccess()
                    when (memberAccess) {
                        is MemberAccessResult.Ready -> {
                            postRpc(
                                rpcName = "get_personal_home_feed_section_payload",
                                payload =
                                    JSONObject()
                                        .put("p_profile_id", memberAccess.profileId)
                                        .put("p_kind", identifier.kind)
                                        .put("p_variant_key", identifier.variantKey)
                                        .put("p_limit", targetLimit)
                                        .toString(),
                                accessToken = memberAccess.accessToken,
                            )
                        }

                        is MemberAccessResult.Unavailable -> {
                            return CatalogPageResult(statusMessage = memberAccess.statusMessage)
                        }
                    }
                }

                HomeCatalogSource.PUBLIC -> {
                    postRpc(
                        rpcName = "get_public_home_feed_section_payload",
                        payload =
                            JSONObject()
                                .put("p_language", publicCatalogLanguageTag())
                                .put("p_kind", identifier.kind)
                                .put("p_variant_key", identifier.variantKey)
                                .put("p_limit", targetLimit)
                                .toString(),
                    )
                }
            }

        val attemptedUrl = buildRpcAttemptedUrl(identifier.source, identifier.kind, identifier.variantKey, targetPage, targetLimit)
        val (list, statusMessage) = parseSupabaseSectionPayloadResponse(response, defaultSource = identifier.source)
        if (list == null) {
            return CatalogPageResult(
                items = emptyList(),
                statusMessage = statusMessage,
                attemptedUrls = listOf(attemptedUrl),
            )
        }

        val startIndex = (targetPage - 1) * targetSize
        val endIndex = minOf(startIndex + targetSize, list.items.size)
        val pageItems = if (startIndex < endIndex) list.items.subList(startIndex, endIndex) else emptyList()
        val pageStatusMessage =
            when {
                pageItems.isNotEmpty() -> ""
                targetPage <= 1 -> statusMessage.ifBlank { "No catalog items available." }
                else -> "No more items available."
            }
        return CatalogPageResult(
            items = pageItems,
            statusMessage = pageStatusMessage,
            attemptedUrls = listOf(attemptedUrl),
        )
    }

    private suspend fun loadSupabaseCatalogSnapshot(
        forceRefresh: Boolean,
        source: HomeCatalogSource,
        memberAccessResult: MemberAccessResult? = null,
    ): SupabaseCatalogSnapshot {
        if (!supabaseAccountClient.isConfigured()) {
            return SupabaseCatalogSnapshot(
                profileId = null,
                lists = emptyList(),
                statusMessage = "Supabase is not configured.",
            )
        }

        val memberAccess =
            if (source == HomeCatalogSource.PERSONAL) {
                when (val access = memberAccessResult ?: resolveMemberAccess()) {
                    is MemberAccessResult.Ready -> access
                    is MemberAccessResult.Unavailable -> {
                        return SupabaseCatalogSnapshot(
                            profileId = null,
                            lists = emptyList(),
                            statusMessage = access.statusMessage,
                        )
                    }
                }
            } else {
                null
            }

        val cacheKey =
            when (source) {
                HomeCatalogSource.PUBLIC -> publicCatalogCacheKey()
                HomeCatalogSource.PERSONAL -> memberAccess?.profileId
            }
        val snapshotProfileId = memberAccess?.profileId

        val cache = catalogCache(source)
        val now = System.currentTimeMillis()
        synchronized(catalogCacheLock) {
            val cachedLists = cache.lists
            val cachedKey = cache.key
            val cacheAgeMs = now - cache.timestampMs
            if (!forceRefresh && cachedLists != null && cachedKey == cacheKey && cacheAgeMs < SUPABASE_CATALOGS_CACHE_TTL_MS) {
                return SupabaseCatalogSnapshot(
                    profileId = snapshotProfileId,
                    lists = cachedLists,
                    statusMessage = "",
                )
            }
        }

        val response =
            when (source) {
                HomeCatalogSource.PERSONAL -> {
                    postRpc(
                        rpcName = "get_personal_home_feed",
                        payload =
                            JSONObject()
                                .put("p_profile_id", memberAccess?.profileId.orEmpty())
                                .put("p_limit", SUPABASE_HOME_FEED_LIMIT)
                                .toString(),
                        accessToken = memberAccess?.accessToken,
                    )
                }

                HomeCatalogSource.PUBLIC -> {
                    postRpc(
                        rpcName = "get_public_home_feed",
                        payload =
                            JSONObject()
                                .put("p_language", publicCatalogLanguageTag())
                                .put("p_limit", SUPABASE_HOME_FEED_LIMIT)
                                .toString(),
                    )
                }
            }

        val (lists, statusMessage) = parseSupabaseFeedResponse(response, defaultSource = source)
        synchronized(catalogCacheLock) {
            cache.key = cacheKey
            cache.lists = lists
            cache.timestampMs = System.currentTimeMillis()
        }

        return SupabaseCatalogSnapshot(
            profileId = snapshotProfileId,
            lists = lists,
            statusMessage = statusMessage,
        )
    }

    private suspend fun selectPrimaryFeedSnapshot(): SupabaseCatalogSnapshot {
        val memberAccess = resolveMemberAccess()
        if (memberAccess is MemberAccessResult.Ready) {
            val personalSnapshot =
                loadSupabaseCatalogSnapshot(
                    forceRefresh = false,
                    source = HomeCatalogSource.PERSONAL,
                    memberAccessResult = memberAccess,
                )
            if (personalSnapshot.lists.isNotEmpty()) {
                return personalSnapshot
            }
        }

        return loadSupabaseCatalogSnapshot(
            forceRefresh = false,
            source = HomeCatalogSource.PUBLIC,
        )
    }

    private suspend fun resolveMemberAccess(): MemberAccessResult {
        if (!supabaseAccountClient.isConfigured()) {
            return MemberAccessResult.Unavailable("Supabase is not configured.")
        }

        val session = runCatching { supabaseAccountClient.ensureValidSession() }.getOrNull()
            ?: return MemberAccessResult.Unavailable("Sign in to Supabase to load catalogs.")

        val profileId = activeProfileStore.getActiveProfileId(session.userId)?.trim()
        if (profileId.isNullOrBlank()) {
            return MemberAccessResult.Unavailable("Select a profile to load catalogs.")
        }

        return MemberAccessResult.Ready(
            accessToken = session.accessToken,
            profileId = profileId,
        )
    }

    private suspend fun postRpc(
        rpcName: String,
        payload: String,
        accessToken: String? = null,
    ): CrispyHttpResponse {
        val url =
            runCatching { "$supabaseBaseUrl/rest/v1/rpc/$rpcName".toHttpUrl() }.getOrElse { error ->
                throw IllegalArgumentException(error.message ?: "Invalid Supabase URL.", error)
            }

        return runCatching {
            httpClient.postJson(
                url = url,
                jsonBody = payload,
                headers = supabaseJsonHeaders(accessToken),
                callTimeoutMs = 12_000L,
            )
        }.getOrElse { error ->
            Log.w(TAG, "Supabase RPC fetch failed for $rpcName", error)
            throw error
        }
    }

    private fun parseSupabaseFeedResponse(
        response: CrispyHttpResponse,
        defaultSource: HomeCatalogSource,
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
            runCatching { parseSupabaseCatalogLists(body, defaultSource) }.getOrElse { error ->
                Log.w(TAG, "Supabase feed parse failed", error)
                return emptyList<SupabaseCatalogList>() to "Failed to parse catalogs."
            }

        if (lists.isEmpty()) {
            return emptyList<SupabaseCatalogList>() to "No catalogs available."
        }
        return lists to ""
    }

    private fun parseSupabaseSectionPayloadResponse(
        response: CrispyHttpResponse,
        defaultSource: HomeCatalogSource,
    ): Pair<SupabaseCatalogList?, String> {
        if (response.code !in 200..299) {
            val msg = extractSupabaseErrorMessage(response.body)
            return null to (msg ?: "Supabase catalogs request failed (HTTP ${response.code}).")
        }

        val body = response.body.trim()
        if (body.isBlank() || body.equals("null", ignoreCase = true)) {
            return null to "No catalog items available."
        }

        val list =
            runCatching { parseSupabaseSectionPayload(body, defaultSource) }.getOrElse { error ->
                Log.w(TAG, "Supabase section payload parse failed", error)
                return null to "Failed to parse catalogs."
            }
        return if (list == null) null to "No catalog items available." else list to ""
    }

    private fun supabaseJsonHeaders(accessToken: String? = null): Headers {
        val builder = Headers.Builder()
            .add("apikey", supabaseAnonKeyValue)
            .add("Accept", "application/json")
            .add("Content-Type", "application/json")
        val token = accessToken?.trim().orEmpty()
        if (token.isNotEmpty()) {
            builder.add("Authorization", "Bearer $token")
        }
        return builder.build()
    }

    private fun extractSupabaseErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return nonBlank(obj.optString("message"))
            ?: nonBlank(obj.optString("msg"))
            ?: nonBlank(obj.optString("error_description"))
            ?: nonBlank(obj.optString("error"))
    }

    private fun parseSupabaseCatalogLists(
        body: String,
        defaultSource: HomeCatalogSource,
    ): List<SupabaseCatalogList> {
        val trimmed = body.trim()
        if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)) {
            return emptyList()
        }

        val listArray =
            when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> JSONArray().put(JSONObject(trimmed))
                else -> return emptyList()
            }

        return buildList {
            for (index in 0 until listArray.length()) {
                val listObj = listArray.optJSONObject(index) ?: continue
                val list = parseSupabaseCatalogList(listObj, defaultSource) ?: continue
                add(list)
            }
        }
    }

    private fun parseSupabaseSectionPayload(
        body: String,
        defaultSource: HomeCatalogSource,
    ): SupabaseCatalogList? {
        val payload =
            when {
                body.startsWith("{") -> JSONObject(body)
                body.startsWith("[") -> JSONArray(body).optJSONObject(0)
                else -> null
            } ?: return null
        return parseSupabaseCatalogList(payload, defaultSource)
    }

    private fun parseSupabaseCatalogList(
        obj: JSONObject,
        defaultSource: HomeCatalogSource,
    ): SupabaseCatalogList? {
        val kind = nonBlank(obj.optString("kind")) ?: return null
        val variantKey = nonBlank(obj.optString("variant_key")) ?: DEFAULT_VARIANT_KEY
        val source = HomeCatalogSource.fromRaw(nonBlank(obj.optString("source"))) ?: defaultSource
        val presentation = HomeCatalogPresentation.fromRaw(nonBlank(obj.optString("presentation")))
        val name = nonBlank(obj.optString("name")).orEmpty()
        val heading = nonBlank(obj.optString("heading")).orEmpty()
        val title = nonBlank(obj.optString("title")).orEmpty()
        val subtitle = nonBlank(obj.optString("subtitle")).orEmpty()
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

        return SupabaseCatalogList(
            kind = kind,
            variantKey = variantKey,
            source = source,
            presentation = presentation,
            name = name,
            heading = heading,
            title = title,
            subtitle = subtitle,
            items = items,
            mediaTypes = items.map { it.type }.toSet(),
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
        val title = nonBlank(obj.optString("title")) ?: nonBlank(obj.optString("name")) ?: return null
        val posterPath = nonBlank(obj.optString("poster_path"))
        val backdropPath = nonBlank(obj.optString("backdrop_path"))

        return CatalogItem(
            id = id,
            title = title,
            posterUrl = TmdbApi.imageUrl(posterPath, size = "w500"),
            backdropUrl = TmdbApi.imageUrl(backdropPath, size = "w780"),
            addonId = "tmdb",
            type = type,
            rating = formatRating(obj.optDoubleOrNull("vote_average")),
            year = null,
            genre = null,
            description = nonBlank(obj.optString("reason")),
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

    private fun catalogCache(source: HomeCatalogSource): SupabaseCatalogCache {
        return when (source) {
            HomeCatalogSource.PERSONAL -> personalCatalogCache
            HomeCatalogSource.PUBLIC -> publicCatalogCache
        }
    }

    private fun buildRpcAttemptedUrl(
        source: HomeCatalogSource,
        kind: String,
        variantKey: String,
        page: Int,
        limit: Int,
    ): String {
        return "supabase:${source.key}:$kind:$variantKey:page=$page:limit=$limit"
    }

    private data class SupabaseCatalogSnapshot(
        val profileId: String?,
        val lists: List<SupabaseCatalogList>,
        val statusMessage: String,
    )

    private class SupabaseCatalogCache {
        @Volatile
        var key: String? = null

        @Volatile
        var lists: List<SupabaseCatalogList>? = null

        @Volatile
        var timestampMs: Long = 0L
    }

    private data class SupabaseCatalogList(
        val kind: String,
        val variantKey: String,
        val source: HomeCatalogSource,
        val presentation: HomeCatalogPresentation,
        val name: String,
        val heading: String,
        val title: String,
        val subtitle: String,
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
            kind = kind,
            variantKey = variantKey,
            source = source,
            presentation = presentation,
            name = name,
            heading = heading,
            title = title,
            subtitle = subtitle,
            items = items.map { it.toDomain() },
            mediaTypes = mediaTypes,
        )
    }

    companion object {
        private const val TAG = "HomeCatalogService"
        private const val DEFAULT_VARIANT_KEY = "default"
        private const val SUPABASE_CATALOGS_CACHE_TTL_MS = 60_000L
        private const val SUPABASE_HOME_FEED_LIMIT = 50
        private const val SUPABASE_SECTION_LIMIT_CAP = 200
    }

    private sealed interface MemberAccessResult {
        data class Ready(
            val accessToken: String,
            val profileId: String,
        ) : MemberAccessResult

        data class Unavailable(
            val statusMessage: String,
        ) : MemberAccessResult
    }

    private fun publicCatalogCacheKey(): String {
        return "public:${publicCatalogLanguageTag()}"
    }

    private fun publicCatalogLanguageTag(): String {
        val locale = Locale.getDefault()
        val languageTag = locale.toLanguageTag().trim()
        return when {
            languageTag.isNotEmpty() && !languageTag.equals("und", ignoreCase = true) -> languageTag
            locale.language.isNotBlank() -> locale.language
            else -> "en"
        }
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

private fun HomeCatalogFeedPlan.toAppModel(): HomePrimaryFeedLoadResult {
    return HomePrimaryFeedLoadResult(
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
        catalogId = catalogId,
        source = source,
        presentation = presentation,
        variantKey = variantKey,
        name = name,
        heading = heading,
        title = title,
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

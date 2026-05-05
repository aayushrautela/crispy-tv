package com.crispy.tv.home

import androidx.compose.runtime.Immutable
import com.crispy.tv.backend.BackendContext
import com.crispy.tv.backend.BackendContextResolver
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.catalog.CatalogPageResult
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.catalog.DiscoverCatalogRef
import com.crispy.tv.domain.home.HomeCatalogItem
import com.crispy.tv.domain.home.HomeCatalogList
import com.crispy.tv.domain.home.HomeCatalogPresentation
import com.crispy.tv.domain.home.HomeCatalogSnapshot
import com.crispy.tv.domain.home.HomeCatalogSource
import com.crispy.tv.domain.home.buildCatalogPage
import com.crispy.tv.domain.home.listDiscoverCatalogs
import com.crispy.tv.domain.home.planPersonalHomeFeed
import com.crispy.tv.ratings.formatRatingOutOfTen
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
private const val DEFAULT_VARIANT_KEY = "default"
private const val PREVIEW_ITEM_LIMIT = 12
private const val RECOMMENDATION_CACHE_MAX_AGE_MS = 15 * 60 * 1000L
private const val GLOBAL_CACHE_KEY = "recommendations_snapshot:last"
private const val DISCOVER_ADDON_NAME = "Crispy"

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
    val mediaKey: String? = null,
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
    val tmdbId: Int? = null,
    val showTmdbId: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val addonId: String?,
    val parentMediaType: String? = null,
    val absoluteEpisodeNumber: Int? = null,
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
    val lookupId: String? = null,
    val tmdbId: Int? = null,
    val showTmdbId: Int? = null,
    val absoluteEpisodeNumber: Int? = null,
)

class RecommendationCatalogService internal constructor(
    private val backendClient: CrispyBackendClient,
    private val backendContextResolver: BackendContextResolver,
    private val diskCacheStore: RecommendationCatalogDiskCacheStore,
) {
    suspend fun loadPrimaryHomeFeed(
        heroLimit: Int = 10,
        sectionLimit: Int = Int.MAX_VALUE,
    ): HomePrimaryFeedLoadResult {
        val snapshot = loadSnapshot()
        val feedPlan = planPersonalHomeFeed(snapshot, heroLimit = heroLimit, sectionLimit = sectionLimit)
        return HomePrimaryFeedLoadResult(
            heroResult =
                HomeHeroLoadResult(
                    items =
                        feedPlan.heroResult.items.mapNotNull { hero ->
                            HomeHeroItem(
                                id = hero.mediaKey,
                                title = hero.title,
                                description = hero.description,
                                rating = hero.rating,
                                year = hero.year,
                                genres = hero.genres,
                                backdropUrl = hero.backdropUrl,
                                addonId = hero.addonId,
                                type = hero.type,
                            )
                        },
                    statusMessage = feedPlan.heroResult.statusMessage,
                ),
            sections =
                feedPlan.sections.map { section ->
                    val previewItems =
                        snapshot.lists
                            .firstOrNull { it.catalogId == section.catalogId }
                            ?.items
                            ?.take(PREVIEW_ITEM_LIMIT)
                            .orEmpty()
                            .mapNotNull { item -> item.toCatalogItem() }
                    CatalogSectionRef(
                        catalogId = section.catalogId,
                        source = section.source,
                        presentation = section.presentation,
                        layout = section.layout.orEmpty(),
                        variantKey = section.variantKey,
                        name = section.name,
                        heading = section.heading,
                        title = section.title,
                        subtitle = section.subtitle,
                        previewItems = previewItems,
                    )
                },
            sectionsStatusMessage = feedPlan.sectionsStatusMessage,
        )
    }

    suspend fun listDiscoverCatalogs(
        mediaType: String? = null,
        limit: Int = Int.MAX_VALUE,
    ): Pair<List<DiscoverCatalogRef>, String> {
        val snapshot = loadSnapshot()
        val (catalogs, statusMessage) = listDiscoverCatalogs(snapshot, mediaType = mediaType, limit = limit)
        return catalogs.map { catalog ->
            DiscoverCatalogRef(
                section =
                    CatalogSectionRef(
                        catalogId = catalog.section.catalogId,
                        source = catalog.section.source,
                        presentation = catalog.section.presentation,
                        layout = catalog.section.layout.orEmpty(),
                        variantKey = catalog.section.variantKey,
                        name = catalog.section.name,
                        heading = catalog.section.heading,
                        title = catalog.section.title,
                        subtitle = catalog.section.subtitle,
                    ),
                addonName = DISCOVER_ADDON_NAME,
                genres = catalog.genres,
            )
        } to statusMessage
    }

    suspend fun fetchCatalogPage(
        section: CatalogSectionRef,
        page: Int,
        pageSize: Int,
    ): CatalogPageResult {
        val snapshot = loadSnapshot()
        val result = buildCatalogPage(snapshot, sectionCatalogId = section.catalogId, page = page, pageSize = pageSize)
        return CatalogPageResult(
            items = result.items.mapNotNull { item -> item.toCatalogItem() },
            statusMessage = result.statusMessage,
            attemptedUrls = listOf(recommendationsAttemptedUrl(snapshot.profileId, section.catalogId, page)),
        )
    }

    private suspend fun loadSnapshot(): HomeCatalogSnapshot {
        val backendContext = getBackendContext()
        if (backendContext == null) {
            return readCachedSnapshot(profileId = null, maxAgeMs = null)
                ?: emptySnapshot("Sign in and select a profile to load recommendations.")
        }

        return runCatching {
            val response = backendClient.getRecommendations(
                accessToken = backendContext.accessToken,
                profileId = backendContext.profileId,
            )
            val snapshot = response?.toSnapshot() ?: emptySnapshot("No recommendations available right now.")
            writeCachedSnapshot(backendContext.profileId, snapshot)
            snapshot
        }.getOrElse { error ->
            readCachedSnapshot(profileId = backendContext.profileId, maxAgeMs = RECOMMENDATION_CACHE_MAX_AGE_MS)
                ?: emptySnapshot(error.message ?: "Failed to load recommendations.")
        }
    }

    private suspend fun getBackendContext(): BackendContext? {
        return backendContextResolver.resolve()
    }

    private fun writeCachedSnapshot(profileId: String, snapshot: HomeCatalogSnapshot) {
        diskCacheStore.write(
            cacheKey = cacheKey(profileId),
            payload = snapshot.toCachePayload(),
        )
        diskCacheStore.write(
            cacheKey = GLOBAL_CACHE_KEY,
            payload = snapshot.toCachePayload(),
        )
    }

    private fun readCachedSnapshot(profileId: String?, maxAgeMs: Long?): HomeCatalogSnapshot? {
        val cacheKeys = buildList {
            profileId?.trim()?.takeIf { it.isNotBlank() }?.let { add(cacheKey(it)) }
            add(GLOBAL_CACHE_KEY)
        }
        return cacheKeys
            .asSequence()
            .mapNotNull { cacheKey -> diskCacheStore.read(cacheKey, maxAgeMs = maxAgeMs)?.payload }
            .mapNotNull { payload -> runCatching { payload.toSnapshot() }.getOrNull() }
            .firstOrNull()
    }

    private fun cacheKey(profileId: String): String {
        return "recommendations_snapshot:${profileId.trim()}"
    }

    private fun emptySnapshot(statusMessage: String): HomeCatalogSnapshot {
        return HomeCatalogSnapshot(
            profileId = null,
            lists = emptyList(),
            statusMessage = statusMessage,
        )
    }

    private fun CrispyBackendClient.RecommendationsResponse.toSnapshot(): HomeCatalogSnapshot {
        return HomeCatalogSnapshot(
            profileId = profileId.takeIf { it.isNotBlank() },
            lists = buildList {
                sections.forEach { section ->
                    section.toCatalogList()?.let(::add)
                }
            },
            statusMessage = if (sections.isEmpty()) "No recommendations available right now." else "",
        )
    }

    private fun CrispyBackendClient.RecommendationSection.toCatalogList(): HomeCatalogList? {
        val catalogItems = when (layout.trim().lowercase(Locale.US)) {
            "hero" -> heroItems.mapNotNull { item -> item.toCatalogItem() }
            "collection" -> collectionItems.mapNotNull { item -> item.toCatalogItem() }
            else -> recommendationItems.mapNotNull { item -> item.media.toCatalogItem() }
        }
        if (catalogItems.isEmpty()) return null
        val meta = recommendationItems.firstOrNull()?.payload.orEmpty()
        return HomeCatalogList(
            kind = id.normalizedKind(),
            variantKey = recommendationVariantKey(id, meta, sourceKey),
            source = HomeCatalogSource.PERSONAL,
            presentation = layout.toPresentation(),
            layout = layout.normalizedBackendLayout(),
            name = title,
            heading = title,
            title = title,
            subtitle = recommendationSubtitle(meta).ifBlank { defaultRecommendationSubtitle(id) },
            items = catalogItems,
            mediaTypes = catalogItems.map { it.type }.toSet(),
        )
    }

    private fun CrispyBackendClient.RuntimeMediaCard.toCatalogItem(): HomeCatalogItem? {
        val normalizedMediaKey = mediaKey.trim().ifBlank { return null }
        val normalizedTitle = title.trim().ifBlank { return null }
        val normalizedType = normalizedCatalogType()
        return HomeCatalogItem(
            mediaKey = normalizedMediaKey,
            title = normalizedTitle,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            addonId = "backend",
            type = normalizedType,
            rating = formatRatingOutOfTen(rating?.toString()),
            year = releaseYear?.toString(),
            description = subtitle,
        )
    }

    private fun CrispyBackendClient.RecommendationHeroItem.toCatalogItem(): HomeCatalogItem? {
        val normalizedMediaKey = mediaKey.trim().ifBlank { return null }
        val normalizedTitle = title.trim().ifBlank { return null }
        val normalizedType = mediaType.toCatalogType()
        return HomeCatalogItem(
            mediaKey = normalizedMediaKey,
            title = normalizedTitle,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            addonId = "backend",
            type = normalizedType,
            rating = formatRatingOutOfTen(rating?.toString()),
            year = releaseYear?.toString(),
            description = description,
        )
    }

    private fun CrispyBackendClient.RecommendationCollectionCard.toCatalogItem(): HomeCatalogItem? {
        val firstItem = items.firstOrNull() ?: return null
        val mediaKey = "collection:${title.trim().lowercase(Locale.US).replace(' ', '-')}"
        return HomeCatalogItem(
            mediaKey = mediaKey,
            title = title.trim(),
            posterUrl = firstItem.posterUrl,
            backdropUrl = null,
            addonId = "backend",
            type = firstItem.mediaType.toCatalogType(),
            rating = formatRatingOutOfTen(firstItem.rating?.toString()),
            year = firstItem.releaseYear?.toString(),
            description = title.trim(),
        )
    }

    private fun HomeCatalogItem.toCatalogItem(): CatalogItem? {
        val normalizedMediaKey = mediaKey.trim().ifBlank { return null }
        return CatalogItem(
            id = normalizedMediaKey,
            mediaKey = normalizedMediaKey,
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

    private fun CrispyBackendClient.RuntimeMediaCard.normalizedCatalogType(): String {
        val normalizedMediaType = mediaType.trim().lowercase(Locale.US)
        return when (normalizedMediaType) {
            "anime" -> "anime"
            "episode", "show", "tv", "series" -> "series"
            else -> "movie"
        }
    }

    private fun String.toCatalogType(): String {
        val normalizedMediaType = trim().lowercase(Locale.US)
        return when (normalizedMediaType) {
            "anime" -> "anime"
            "episode", "show", "tv", "series" -> "series"
            else -> "movie"
        }
    }

    private fun String.normalizedKind(): String {
        return trim().ifBlank { "home" }
    }

    private fun String.normalizedVariantKey(): String {
        return trim().ifBlank { DEFAULT_VARIANT_KEY }
    }

    private fun String.toPresentation(): HomeCatalogPresentation {
        return if (equals("hero", ignoreCase = true) || equals("landscape", ignoreCase = true)) {
            HomeCatalogPresentation.HERO
        } else {
            HomeCatalogPresentation.RAIL
        }
    }

    private fun String.normalizedBackendLayout(): String {
        return when (trim().lowercase(Locale.US)) {
            "hero" -> "hero"
            "landscape" -> "landscape"
            "collection" -> "collection"
            else -> "regular"
        }
    }

    private fun defaultRecommendationSubtitle(sectionId: String): String {
        return when (sectionId.trim().lowercase(Locale.US)) {
            "up-next" -> "Upcoming episodes to keep moving."
            "this-week" -> "Episodes airing this week."
            "recently-released" -> "Freshly released episodes."
            else -> ""
        }
    }

    private fun recommendationSubtitle(meta: Map<String, Any?>): String {
        return meta["subtitle"]?.toString()?.trim().orEmpty()
    }

    private fun recommendationVariantKey(id: String, meta: Map<String, Any?>, source: String): String {
        val metaKey =
            listOf("key", "variantKey", "slug", "strategy")
                .firstNotNullOfOrNull { key -> meta[key]?.toString()?.trim()?.takeIf { it.isNotBlank() } }
        return metaKey ?: id.normalizedVariantKey().takeIf { it != DEFAULT_VARIANT_KEY } ?: source.normalizedVariantKey()
    }

    private fun HomeCatalogSnapshot.toCachePayload(): String {
        return JSONObject()
            .put("profile_id", profileId)
            .put("status_message", statusMessage)
            .put(
                "lists",
                JSONArray().apply {
                    lists.forEach { list ->
                        put(
                            JSONObject()
                                .put("kind", list.kind)
                                .put("variant_key", list.variantKey)
                                .put("source", list.source.key)
                                .put("presentation", list.presentation.key)
                                .put("layout", list.layout)
                                .put("name", list.name)
                                .put("heading", list.heading)
                                .put("title", list.title)
                                .put("subtitle", list.subtitle)
                                .put("media_types", JSONArray(list.mediaTypes.toList()))
                                .put(
                                    "items",
                                    JSONArray().apply {
                                                list.items.forEach { item ->
                                                    put(
                                                        JSONObject()
                                                            .put("media_key", item.mediaKey)
                                                            .put("title", item.title)
                                                            .put("poster_url", item.posterUrl)
                                                            .put("backdrop_url", item.backdropUrl)
                                                            .put("addon_id", item.addonId)
                                                            .put("type", item.type)
                                                            .put("rating", item.rating)
                                                            .put("year", item.year)
                                                            .put("description", item.description)
                                            )
                                        }
                                    },
                                )
                        )
                    }
                },
            ).toString()
    }

    private fun String.toSnapshot(): HomeCatalogSnapshot {
        val json = JSONObject(this)
        val listsJson = json.optJSONArray("lists") ?: JSONArray()
        val lists = buildList {
            for (index in 0 until listsJson.length()) {
                val entry = listsJson.optJSONObject(index) ?: continue
                parseCachedList(entry)?.let(::add)
            }
        }
        return HomeCatalogSnapshot(
            profileId = json.optString("profile_id").trim().ifBlank { null },
            lists = lists,
            statusMessage = json.optString("status_message").trim(),
        )
    }

    private fun parseCachedList(json: JSONObject): HomeCatalogList? {
        val kind = json.optString("kind").trim().ifBlank { return null }
        val source = HomeCatalogSource.fromRaw(json.optString("source")) ?: HomeCatalogSource.PERSONAL
        val presentation = HomeCatalogPresentation.fromRaw(json.optString("presentation"))
        val mediaTypes = buildSet {
            val array = json.optJSONArray("media_types") ?: JSONArray()
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }
        val items = buildList {
            val array = json.optJSONArray("items") ?: JSONArray()
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                parseCachedItem(entry)?.let(::add)
            }
        }
        return HomeCatalogList(
            kind = kind,
            variantKey = json.optString("variant_key").trim().ifBlank { DEFAULT_VARIANT_KEY },
            source = source,
            presentation = presentation,
            layout = json.optString("layout").trim().ifBlank { null },
            name = json.optString("name").trim(),
            heading = json.optString("heading").trim(),
            title = json.optString("title").trim(),
            subtitle = json.optString("subtitle").trim(),
            items = items,
            mediaTypes = mediaTypes,
        )
    }

    private fun parseCachedItem(json: JSONObject): HomeCatalogItem? {
        val mediaKey = json.optString("media_key").trim()
        val title = json.optString("title").trim()
        val addonId = json.optString("addon_id").trim()
        val type = json.optString("type").trim()
        if (mediaKey.isBlank() || title.isBlank() || addonId.isBlank() || type.isBlank()) {
            return null
        }
        return HomeCatalogItem(
            mediaKey = mediaKey,
            title = title,
            posterUrl = json.optString("poster_url").trim().ifBlank { null },
            backdropUrl = json.optString("backdrop_url").trim().ifBlank { null },
            addonId = addonId,
            type = type,
            rating = json.optString("rating").trim().ifBlank { null },
            year = json.optString("year").trim().ifBlank { null },
            description = json.optString("description").trim().ifBlank { null },
        )
    }

    private fun recommendationsAttemptedUrl(profileId: String?, catalogId: String? = null, page: Int? = null): String {
        val base = "backend:/v1/profiles/${profileId.orEmpty()}/recommendations"
        val suffix = buildList {
            catalogId?.trim()?.takeIf { it.isNotBlank() }?.let { add("catalogId=$it") }
            page?.let { add("page=$it") }
        }.joinToString("&")
        return if (suffix.isBlank()) base else "$base?$suffix"
    }
}

package com.crispy.tv.library

import android.content.Context
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.images.ResponsiveImageSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Disk cache for the first page of each library section (history, watchlist,
 * ratings) per profile. No time-based expiry: a cached entry is valid until it
 * is explicitly invalidated (server signal, generation advance, or local
 * optimistic write). Deeper pages are always served from the network.
 */
class LibraryDiskCacheStore(appContext: Context) {
    private val cacheDirectory = appContext.filesDir.resolve(CACHE_DIRECTORY_NAME).also { directory ->
        if (!directory.exists()) {
            directory.mkdirs()
        }
    }

    suspend fun read(profileId: String, sectionId: String): LibraryCachedPage? = withContext(Dispatchers.IO) {
        val file = cacheFile(profileId, sectionId)
        val raw = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull() ?: return@withContext null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext null
        val items = parseItems(json.optJSONArray("items"))
        if (items.isEmpty() && !json.has("items")) {
            return@withContext null
        }
        LibraryCachedPage(
            items = items,
            nextCursor = json.optNullableString("next_cursor"),
            hasMore = json.optBoolean("has_more", false),
            appliedGenerationMs = json.optLong("applied_gen_ms", 0L).takeIf { it > 0L },
        )
    }

    suspend fun write(
        profileId: String,
        sectionId: String,
        page: LibrarySectionPageUi,
        appliedGenerationMs: Long?,
    ) = withContext(Dispatchers.IO) {
        val json =
            JSONObject()
                .put(
                    "items",
                    JSONArray().apply {
                        page.items.forEach { item ->
                            put(item.toCacheJson())
                        }
                    },
                )
                .put("next_cursor", page.nextCursor)
                .put("has_more", page.hasMore)
                .apply {
                    if (appliedGenerationMs != null && appliedGenerationMs > 0L) {
                        put("applied_gen_ms", appliedGenerationMs)
                    }
                }
                .toString()
        val file = cacheFile(profileId, sectionId)
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json, StandardCharsets.UTF_8)
        }
    }

    suspend fun invalidate(profileId: String, sectionId: String) = withContext(Dispatchers.IO) {
        runCatching { cacheFile(profileId, sectionId).delete() }
    }

    private fun cacheFile(profileId: String, sectionId: String): File {
        return cacheDirectory.resolve("${cacheKey(profileId, sectionId).sha256()}.json")
    }

    private fun cacheKey(profileId: String, sectionId: String): String {
        return "library:${profileId.trim()}:${sectionId.trim()}"
    }

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(StandardCharsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                append(((byte.toInt() ushr 4) and 0x0F).toString(16))
                append((byte.toInt() and 0x0F).toString(16))
            }
        }
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotEmpty() }
    }

    private fun parseItems(array: JSONArray?): List<CatalogItem> {
        val safe = array ?: return emptyList()
        return buildList {
            for (i in 0 until safe.length()) {
                val item = safe.optJSONObject(i) ?: continue
                add(item.toCatalogItem() ?: continue)
            }
        }
    }

    private fun JSONObject.toCatalogItem(): CatalogItem? {
        val normalizedItemId = optNullableString("item_id") ?: return null
        val normalizedTitle = optNullableString("title") ?: return null
        return CatalogItem(
            id = normalizedItemId,
            itemId = optNullableString("entity_item_id") ?: normalizedItemId,
            title = normalizedTitle,
            artworkUrl = optNullableString("artwork_url"),
            logoUrl = optNullableString("logo_url"),
            artwork = parseResponsiveImage("artwork"),
            logo = parseResponsiveImage("logo"),
            addonId = optNullableString("addon_id") ?: "backend",
            type = optNullableString("type") ?: "movie",
            rating = optNullableString("rating"),
            year = optNullableString("year"),
            genre = optNullableString("genre"),
            maturityRating = optNullableString("maturity_rating"),
            ratingValue = optInt("rating_value", 0).takeIf { it > 0 },
            addedAt = optNullableString("added_at"),
            watchedAt = optNullableString("watched_at"),
            ratedAt = optNullableString("rated_at"),
            lastActivityAt = optNullableString("last_activity_at"),
            episodeCount = optInt("episode_count", 0).takeIf { it > 0 },
        )
    }

    private fun CatalogItem.toCacheJson(): JSONObject {
        return JSONObject()
            .put("item_id", id)
            .put("entity_item_id", itemId)
            .put("title", title)
            .put("artwork_url", artworkUrl)
            .put("logo_url", logoUrl)
            .put("artwork", artwork?.toJson())
            .put("logo", logo?.toJson())
            .put("addon_id", addonId)
            .put("type", type)
            .put("rating", rating)
            .put("year", year)
            .put("genre", genre)
            .put("maturity_rating", maturityRating)
            .put("rating_value", ratingValue)
            .put("added_at", addedAt)
            .put("watched_at", watchedAt)
            .put("rated_at", ratedAt)
            .put("last_activity_at", lastActivityAt)
            .put("episode_count", episodeCount)
    }

    private fun ResponsiveImageSet.toJson(): JSONObject {
        return JSONObject()
            .put("low", low)
            .put("medium", medium)
            .put("high", high)
    }

    private fun JSONObject.parseResponsiveImage(key: String): ResponsiveImageSet? {
        val json = optJSONObject(key) ?: return null
        val low = json.optNullableString("low")
        val medium = json.optNullableString("medium")
        val high = json.optNullableString("high")
        if (low == null && medium == null && high == null) return null
        return ResponsiveImageSet(low = low, medium = medium, high = high)
    }

    private companion object {
        private const val CACHE_DIRECTORY_NAME = "library_section_cache"
    }
}

data class LibraryCachedPage(
    val items: List<CatalogItem>,
    val nextCursor: String?,
    val hasMore: Boolean,
    val appliedGenerationMs: Long?,
)
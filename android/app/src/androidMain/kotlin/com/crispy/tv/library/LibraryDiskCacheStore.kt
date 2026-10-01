package com.crispy.tv.library

import android.content.Context
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.images.ResponsiveImageSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Disk cache for the first page of each library section (history, watchlist,
 * ratings) per profile. No time-based expiry: a cached entry is valid until it
 * is explicitly invalidated (server signal, generation advance, or local
 * optimistic write). Deeper pages are always served from the network.
 */
class LibraryDiskCacheStore(appContext: Context) : LibraryDiskCache {
    private val cacheDirectory = appContext.filesDir.resolve(CACHE_DIRECTORY_NAME).also { directory ->
        if (!directory.exists()) {
            directory.mkdirs()
        }
    }

    override suspend fun read(profileId: String, sectionId: String): LibraryCachedPage? = withContext(Dispatchers.IO) {
        val file = cacheFile(profileId, sectionId)
        val raw = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull() ?: return@withContext null
        val json = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return@withContext null
        val items = parseItems(json.optJsonArray("items"))
        // `has("items")` asks whether the KEY is there, which is not the same
        // question as `optJsonArray` -- so it survives the port rather than
        // collapsing into it. A key present with a null or non-array value is
        // "present" here and "absent" there.
        if (items.isEmpty() && "items" !in json) {
            return@withContext null
        }
        LibraryCachedPage(
            items = items,
            nextCursor = json.optNullableString("next_cursor"),
            hasMore = json.optBooleanOrNull("has_more") ?: false,
            appliedGenerationMs = (json.optLongOrNull("applied_gen_ms") ?: 0L).takeIf { it > 0L },
        )
    }

    override suspend fun write(
        profileId: String,
        sectionId: String,
        page: LibrarySectionPageUi,
        appliedGenerationMs: Long?,
    ) = withContext(Dispatchers.IO) {
        // A chained `JSONObject().put(...).put(...)` becomes ONE
        // `buildJsonObject { }` block, and the chain's `.apply { ... }` becomes a
        // plain `if` inside it: there is no receiver left to apply to.
        // `.toString()` stays, because the cache is written as text either way.
        //
        // **`put` here is the BUILDER's extension, not `Map.put`.** Inside the
        // lambda `JsonObjectBuilder` is the receiver, so `put` resolves to
        // `JsonObjectBuilder.put`; outside one the name would find
        // `Map<String, JsonElement>.put`, whose value parameter is a non-null
        // `JsonElement` — which is why the nullable `next_cursor` needs its
        // `?.let` below and the two nested `put("items", ...)` cannot.
        val json =
            buildJsonObject {
                put(
                    "items",
                    buildJsonArray {
                        page.items.forEach { item ->
                            add(item.toCacheJson())
                        }
                    },
                )
                // `org.json`'s `put(key, null)` REMOVED the key rather than
                // writing one, and `put(key, value: String?)` writes `JsonNull`
                // instead. The two read back identically — `optStringOrEmpty`
                // answers `""` for an absent key and for a `JsonNull` alike — but
                // the `?.let` also keeps the stored bytes unchanged, which is
                // worth more than the shorter line for a file on disk.
                page.nextCursor?.let { put("next_cursor", it) }
                put("has_more", page.hasMore)
                if (appliedGenerationMs != null && appliedGenerationMs > 0L) {
                    put("applied_gen_ms", appliedGenerationMs)
                }
            }.toString()
        val file = cacheFile(profileId, sectionId)
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json, StandardCharsets.UTF_8)
        }
    }

    /**
     * Not on [LibraryDiskCache]: the screen calls this, the paging source does not.
     */
    override suspend fun invalidate(profileId: String, sectionId: String) = withContext(Dispatchers.IO) {
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

    private fun parseItems(array: JsonArray?): List<CatalogItem> {
        val safe = array ?: return emptyList()
        return buildList {
            for (item in safe) {
                if (item !is JsonObject) continue
                add(item.toCatalogItem() ?: continue)
            }
        }
    }

    private fun JsonObject.toCatalogItem(): CatalogItem? {
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
            liked = optBooleanOrNull("liked"),
            addedAt = optNullableString("added_at"),
            watchedAt = optNullableString("watched_at"),
            ratedAt = optNullableString("rated_at"),
            lastActivityAt = optNullableString("last_activity_at"),
            // `optInt("episode_count", 0).takeIf { it > 0 }` is a default read
            // whose default is load-bearing: 0 and absent are the same answer
            // here on purpose, because the filter below rejects both. So the
            // nullable form with the old default is the faithful translation,
            // NOT `optIntOrNull` -- a missing count must not become null and
            // then reach `.takeIf` as a different type.
            episodeCount = (optIntOrNull("episode_count") ?: 0).takeIf { it > 0 },
        )
    }

    private fun CatalogItem.toCacheJson(): JsonObject {
        return buildJsonObject {
            put("item_id", id)
            put("entity_item_id", itemId)
            put("title", title)
            put("artwork_url", artworkUrl)
            put("logo_url", logoUrl)
            // `Map<String, JsonElement>.put` takes a non-null `JsonElement`, and
            // `toJson()` answers `JsonObject?`, so the nullable argument needs the
            // builder overload — which `?.let` also scopes to the non-null case. This
            // is the same absent-key-versus-`JsonNull` question as `next_cursor`
            // above, and it has the same answer.
            artwork?.toJson()?.let { put("artwork", it) }
            logo?.toJson()?.let { put("logo", it) }
            put("addon_id", addonId)
            put("type", type)
            put("rating", rating)
            put("year", year)
            put("genre", genre)
            put("maturity_rating", maturityRating)
            put("liked", liked?.let { JsonPrimitive(it) } ?: JsonNull)
            put("added_at", addedAt)
            put("watched_at", watchedAt)
            put("rated_at", ratedAt)
            put("last_activity_at", lastActivityAt)
            put("episode_count", episodeCount)
        }
    }

    private fun ResponsiveImageSet.toJson(): JsonObject {
        return buildJsonObject {
            put("low", low)
            put("medium", medium)
            put("high", high)
        }
    }

    private fun JsonObject.parseResponsiveImage(key: String): ResponsiveImageSet? {
        val json = optJsonObject(key) ?: return null
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

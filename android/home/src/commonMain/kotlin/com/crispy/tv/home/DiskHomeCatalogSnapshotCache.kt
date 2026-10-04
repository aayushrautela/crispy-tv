package com.crispy.tv.home

import com.crispy.tv.domain.home.HomeCatalogItem
import com.crispy.tv.domain.home.HomeCatalogList
import com.crispy.tv.domain.home.HomeCatalogPresentation
import com.crispy.tv.domain.home.HomeCatalogSnapshot
import com.crispy.tv.domain.home.HomeCatalogSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The [HomeCatalogSnapshotCache] backed by [RecommendationCatalogDiskCacheStore]: one file
 * per cache key, holding the snapshot as JSON.
 *
 * **This class was `androidMain`, and `org.json` rather than the `Context` is what stopped
 * it** — `JSONObject` is what every `put`/`opt` in the mapping is written against, so
 * wrapping the accessors moved nothing. `WatchProgressStore` made this exact conversion to
 * `kotlinx.serialization.json` first, and `:app`'s `LibraryDiskCacheStore` is the same
 * shape of port one module over. So the two ports that were genuinely missing — `org.json`
 * and `java.io` — were the same port `:app` had already discharged, and this file is the
 * third place that answer exists rather than a fourth implementation of the question.
 *
 * ## What the null handling is for, and what is *not* claimed about it
 *
 * `org.json`'s `put(key, null)` **removes** the key, so every nullable field below is
 * written by `?.let { put(key, it) }` rather than by handing the builder a null: the
 * builder's nullable `put` would write `JsonNull` instead. Both *read* identically —
 * `optStringOrEmpty` answers `""` for an absent key and for a `JsonNull` alike — so this is
 * not about behaviour; it is about a file on disk that an older build of this app can still
 * read, and about the two shapes being indistinguishable in a review. The exception is
 * the two artwork maps, whose value type is `Map<String, String?>`: `JSONObject(map)`
 * wrapped a null value as `JSONObject.NULL`, so those entries *are* written as `JsonNull`.
 *
 * **No claim is made here that the bytes are identical to the old writer's, because the
 * old writer is no longer in the tree to compare against** — `androidMain`'s copy of this
 * class was an uncommitted working file, so there is nothing to diff and no golden anyone
 * could have written from it. What *is* pinned, by `DiskHomeCatalogSnapshotCacheTest`:
 * the set of keys this writer emits, which key each null scalar omits, that a null value
 * inside the artwork/logo maps is `JsonNull` rather than an omitted entry, and that the
 * reader accepts a payload written in the old shape (scalars absent, containers empty)
 * as well as one this writer produced. The consequence of a genuine format divergence is
 * bounded — these are cache files under the app's own directory, and every failure to
 * parse answers `null`, which is the cold-start answer — but a *renamed* file would not
 * be bounded that way, which is why [cacheFileName] is pinned separately.
 *
 * **The writer writes one payload under both keys**, the per-profile one and
 * [GLOBAL_CACHE_KEY], which is why [read] walks them in that order and why the expiry
 * could be folded into the read.
 */
class DiskHomeCatalogSnapshotCache(
    private val diskCacheStore: RecommendationCatalogDiskCacheStore,
) : HomeCatalogSnapshotCache {

    override suspend fun read(profileId: String?): CachedHomeCatalogSnapshot? {
        for (key in cacheKeys(profileId)) {
            val payload = diskCacheStore.read(key, maxAgeMs = null)?.payload ?: continue
            val snapshot = runCatching { payload.toSnapshot() }.getOrNull() ?: continue
            // The expiry travels inside the payload rather than beside it, so reading it
            // costs no second walk of the cache keys. `org.json`'s `optString` answered
            // `""` for a JSON null here, so a snapshot stored without an expiry and one
            // stored with a null expiry are the same answer.
            val expiresAt = runCatching { Json.parseToJsonElement(payload).jsonObject }
                .getOrNull()
                ?.optStringOrEmpty("expires_at")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            return CachedHomeCatalogSnapshot(snapshot = snapshot, expiresAtIso = expiresAt)
        }
        return null
    }

    override suspend fun write(
        profileId: String?,
        snapshot: HomeCatalogSnapshot,
        expiresAtIso: String?,
    ) {
        val normalizedExpiresAt = expiresAtIso?.trim()
        // The key order is the old chain's order and nothing reads it, but it is pinned
        // anyway: the key *set* is what a future reader of this file has to agree with,
        // and `DiskHomeCatalogSnapshotCacheTest` asserts it as a set precisely because a
        // reordered builder would be invisible to a round-trip test.
        val payloadJson = buildJsonObject {
            snapshot.profileId?.let { put("profile_id", it) }
            put("status_message", snapshot.statusMessage)
            put("lists", snapshot.lists.listsToCacheJson())
            if (!normalizedExpiresAt.isNullOrBlank()) {
                put("expires_at", normalizedExpiresAt)
            }
        }.toString()
        for (key in cacheKeys(profileId)) {
            diskCacheStore.write(cacheKey = key, payload = payloadJson)
        }
    }

    private fun cacheKeys(profileId: String?): List<String> {
        return buildList {
            profileId?.trim()?.takeIf { it.isNotBlank() }?.let { add(homeCacheKey(it)) }
            add(GLOBAL_CACHE_KEY)
        }
    }

    private fun List<HomeCatalogList>.listsToCacheJson(): JsonArray = buildJsonArray {
        forEach { list ->
            add(
                buildJsonObject {
                    put("kind", list.kind)
                    put("variant_key", list.variantKey)
                    put("source", list.source.key)
                    put("presentation", list.presentation.key)
                    list.layout?.let { put("layout", it) }
                    put("name", list.name)
                    put("heading", list.heading)
                    put("title", list.title)
                    put("subtitle", list.subtitle)
                    put("media_types", buildJsonArray { list.mediaTypes.forEach { add(JsonPrimitive(it)) } })
                    put("items", list.items.itemsToCacheJson())
                },
            )
        }
    }

    private fun List<HomeCatalogItem>.itemsToCacheJson(): JsonArray = buildJsonArray {
        forEach { item ->
            add(
                buildJsonObject {
                    put("item_id", item.itemId)
                    put("title", item.title)
                    item.artworkUrl?.let { put("artwork_url", it) }
                    item.logoUrl?.let { put("logo_url", it) }
                    // `JSONObject(map)` wrote an absent key for a null *value* rather than
                    // omitting the entry, so a null here is `JsonNull` and not a skipped
                    // `put` — the two differ in the bytes on disk, and this one is not the
                    // shape the old writer produced.
                    put("artwork", item.artwork.toCacheJson())
                    put("logo", item.logo.toCacheJson())
                    put("addon_id", item.addonId)
                    put("type", item.type)
                    item.rating?.let { put("rating", it) }
                    item.year?.let { put("year", it) }
                    item.genre?.let { put("genre", it) }
                    item.description?.let { put("description", it) }
                    item.tagline?.let { put("tagline", it) }
                },
            )
        }
    }

    private fun Map<String, String?>.toCacheJson(): JsonObject = buildJsonObject {
        forEach { (key, value) ->
            put(key, value?.let(::JsonPrimitive) ?: JsonNull)
        }
    }

    private fun String.toSnapshot(): HomeCatalogSnapshot {
        val json = Json.parseToJsonElement(this).jsonObject
        val listsJson = json.optJsonArray("lists")
        val lists = buildList {
            for (element in listsJson.orEmpty()) {
                if (element !is JsonObject) continue
                parseCachedList(element)?.let(::add)
            }
        }
        return HomeCatalogSnapshot(
            profileId = json.optStringOrEmpty("profile_id").trim().ifBlank { null },
            lists = lists,
            statusMessage = json.optStringOrEmpty("status_message").trim(),
        )
    }

    private fun parseCachedList(json: JsonObject): HomeCatalogList? {
        val kind = json.optStringOrEmpty("kind").trim().ifBlank { return null }
        val source = HomeCatalogSource.fromRaw(json.optStringOrEmpty("source")) ?: HomeCatalogSource.PERSONAL
        val presentation = HomeCatalogPresentation.fromRaw(json.optStringOrEmpty("presentation"))
        val mediaTypes = buildSet {
            for (element in json.optJsonArray("media_types").orEmpty()) {
                element.asCacheString().trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }
        val items = buildList {
            for (element in json.optJsonArray("items").orEmpty()) {
                if (element !is JsonObject) continue
                parseCachedItem(element)?.let(::add)
            }
        }
        return HomeCatalogList(
            kind = kind,
            variantKey = json.optStringOrEmpty("variant_key").trim().ifBlank { DEFAULT_VARIANT_KEY },
            source = source,
            presentation = presentation,
            layout = json.optStringOrEmpty("layout").trim().ifBlank { null },
            name = json.optStringOrEmpty("name").trim(),
            heading = json.optStringOrEmpty("heading").trim(),
            title = json.optStringOrEmpty("title").trim(),
            subtitle = json.optStringOrEmpty("subtitle").trim(),
            items = items,
            mediaTypes = mediaTypes,
        )
    }

    private fun parseCachedItem(json: JsonObject): HomeCatalogItem? {
        val itemId = json.optStringOrEmpty("item_id").trim()
        val title = json.optStringOrEmpty("title").trim()
        val addonId = json.optStringOrEmpty("addon_id").trim()
        val type = json.optStringOrEmpty("type").trim()
        if (itemId.isBlank() || title.isBlank() || addonId.isBlank() || type.isBlank()) {
            return null
        }
        return HomeCatalogItem(
            itemId = itemId,
            title = title,
            artworkUrl = json.optStringOrEmpty("artwork_url").trim().ifBlank { null },
            logoUrl = json.optStringOrEmpty("logo_url").trim().ifBlank { null },
            artwork = json.optJsonObject("artwork").toStringMap(),
            logo = json.optJsonObject("logo").toStringMap(),
            addonId = addonId,
            type = type,
            rating = json.optStringOrEmpty("rating").trim().ifBlank { null },
            year = json.optStringOrEmpty("year").trim().ifBlank { null },
            genre = json.optStringOrEmpty("genre").trim().ifBlank { null },
            description = json.optStringOrEmpty("description").trim().ifBlank { null },
            tagline = json.optStringOrEmpty("tagline").trim().ifBlank { null },
        )
    }
}

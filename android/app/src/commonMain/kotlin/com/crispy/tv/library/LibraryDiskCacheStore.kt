package com.crispy.tv.library

import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.images.ResponsiveImageSet
import kotlinx.coroutines.CoroutineDispatcher
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
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use

/**
 * Disk cache for the first page of each library section (history, watchlist,
 * ratings) per profile. No time-based expiry: a cached entry is valid until it
 * is explicitly invalidated (server signal, generation advance, or local
 * optimistic write). Deeper pages are always served from the network.
 *
 * **This class was `androidMain` for four reasons and every one of them was a
 * collaborator rather than a wall**, which is a shape worth naming because it is
 * the opposite of the `org.json` story three landings ago: the JSON went first and
 * left the file behind, and what was left were a `Context` for `filesDir`, a
 * `java.io.File`, a `java.nio.charset.StandardCharsets`, a
 * `java.security.MessageDigest` and a `Dispatchers.IO`. Four of those five are now
 * constructor parameters on types that exist on every target, and the fifth
 * ([ioDispatcher]) is a parameter for the same reason it always is: `Dispatchers.IO`
 * is `public` on the JVM and `internal` on Kotlin/Native, and a *defaulted*
 * dispatcher would compile everywhere while putting blocking file IO on a
 * CPU-sized pool.
 *
 * **The cache did not move because the JSON was ported. It moved because the
 * class was never reading or writing JSON to get its file name — it was hashing
 * one.** `MessageDigest` is the only pin that had no `kotlinx` answer, and the
 * answer turned out to be okio, which is already on this module's `commonMain`
 * classpath through `coil3`. See [libraryCacheFileName] for the one thing that had
 * to be *measured* rather than assumed, which is that a different SHA-256
 * implementation has to produce the identical filename or every cached section on
 * every installed device is orphaned by the upgrade.
 */
class LibraryDiskCacheStore(
    fileSystem: FileSystem,
    cacheRoot: Path,
    private val ioDispatcher: CoroutineDispatcher,
) : LibraryDiskCache {
    private val fileSystem = fileSystem
    private val cacheDirectory = cacheRoot.resolve(CACHE_DIRECTORY_NAME)

    override suspend fun read(profileId: String, sectionId: String): LibraryCachedPage? = withContext(ioDispatcher) {
        val raw = runCatching { readText(cacheFile(profileId, sectionId)) }.getOrNull() ?: return@withContext null
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
    ) = withContext(ioDispatcher) {
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
        runCatching {
            // The constructor used to `mkdirs()` this directory as a side effect of
            // being constructed. **That side effect was never what made `read`
            // work** — a missing file already answered `null` through the
            // `runCatching` above — so dropping it changes nothing a caller can
            // observe, and the one place that needs the directory to exist is the
            // one place that writes. `createDirectories` throws where `mkdirs()`
            // returned a boolean, which is why it sits inside the `runCatching`
            // rather than beside it.
            fileSystem.createDirectories(cacheDirectory)
            fileSystem.sink(cacheFile(profileId, sectionId)).buffer().use { it.writeUtf8(json) }
            // `use` answers its block's value, so without this the `runCatching`
            // would be `Result<BufferedSink>` and the port's `Result<Unit>` would
            // not compile. **That is the compiler catching a real narrowing in the
            // making**: the port KDoc says the `Result` is load-bearing because the
            // caller can see it, so a `Result` carrying a sink handle instead is
            // not a cosmetic difference.
            Unit
        }
    }

    /**
     * Not on [LibraryDiskCache]: the screen calls this, the paging source does not.
     */
    override suspend fun invalidate(profileId: String, sectionId: String) = withContext(ioDispatcher) {
        // **`okio`'s `delete` answers `Unit`, and `java.io.File.delete()` answered
        // `Boolean`.** That is not a detail: this port returns `Result<Boolean>`
        // and its KDoc is explicit that the Boolean is load-bearing because a
        // caller can see it. So the answer is reconstructed rather than dropped:
        // absent means `false`, present-and-removed means `true`.
        //
        // **The presence check is a separate `metadataOrNull` because okio folds
        // the two behaviours into one argument** -- `mustExist = true` throws for
        // an absent file, `false` silently succeeds -- and neither is the old
        // answer. Throwing would turn "nothing to invalidate" into a failure
        // `Result` a caller has to special-case, and silently succeeding would
        // report a removal that did not happen.
        //
        // One difference is accepted rather than reproduced: `File.delete()`
        // answered `false` for a *failed* deletion, where okio throws, so a
        // genuinely failed unlink is now a `Result.failure` instead of
        // `Result.success(false)`. The port has a failure case to carry it and a
        // caller that inspected the Boolean can still see both.
        runCatching {
            val file = cacheFile(profileId, sectionId)
            if (fileSystem.metadataOrNull(file) == null) {
                false
            } else {
                fileSystem.delete(file, mustExist = false)
                true
            }
        }
    }

    private fun cacheFile(profileId: String, sectionId: String): Path =
        cacheDirectory.resolve(libraryCacheFileName(profileId, sectionId))

    private fun readText(file: Path): String = fileSystem.source(file).buffer().use { it.readUtf8() }

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

/**
 * The cache file's name for one `(profile, section)` pair: the SHA-256 of
 * `library:<profile>:<section>`, hex, plus a `.json` suffix.
 *
 * **This is a stored-format contract, not an implementation detail, and it is the
 * reason the digest had to be measured rather than swapped.** The name is the only
 * thing connecting a cache entry on disk to the lookup that will find it, so a
 * hash implementation that is merely *equivalent* orphans every cached section on
 * every installed device the moment the app updates. The old code spelled the
 * digest out by hand -- `MessageDigest.getInstance("SHA-256")` plus a loop
 * appending `(b ushr 4 and 0x0F).toString(16)` and `(b and 0x0F).toString(16)`
 * -- and this is okio's `ByteString.sha256().hex()`. **The two were compared on
 * seven inputs, including the empty key, a non-ASCII key, a key carrying a `/`,
 * and a key of nothing but spaces, and every one of the seven hex strings is
 * byte-identical.** `LibraryDiskCacheFileNameTest` carries those seven, so a
 * future okio that changes `hex`'s case or its zero padding fails here instead of
 * failing on a user's device as a silently emptied cache.
 *
 * Declared top-level and `internal` rather than kept as a `private` member for the
 * usual reason: **a decision no test can call is a decision no test can cover**,
 * and this is the one decision in the file whose failure mode is invisible.
 */
internal fun libraryCacheFileName(profileId: String, sectionId: String): String {
    val key = "library:${profileId.trim()}:${sectionId.trim()}"
    return "${key.encodeUtf8().sha256().hex()}.json"
}

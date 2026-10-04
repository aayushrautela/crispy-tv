package com.crispy.tv.home

import com.crispy.tv.platform.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use

/**
 * One cached JSON payload per cache key on disk, with the instant it was written.
 *
 * **This class was `androidMain` for five pins and every one of them is now a
 * constructor parameter**, which is the same shape as `:app`'s `LibraryDiskCacheStore`
 * — and for the same reason: `Context` was for `filesDir`, `java.io.File` for the file,
 * `java.nio.charset.StandardCharsets` for UTF-8, `java.security.MessageDigest` for the
 * file name, and `Dispatchers.IO` for the blocking calls. The last of those cannot be
 * defaulted, because `Dispatchers.IO` is `public` on the JVM and `internal` on
 * Kotlin/Native: a defaulted dispatcher compiles on every target and puts blocking file
 * IO on a CPU-sized pool.
 *
 * **The [FileSystem] is required too, and its absence from `commonMain` is measured
 * rather than remembered.** This first landed with `fileSystem: FileSystem =
 * FileSystem.SYSTEM`, which compiles and passes on the JVM and on Apple and fails
 * `:android:home:compileCommonMainKotlinMetadata` with `Unresolved reference 'SYSTEM'`:
 * `SYSTEM` is not in the okio version resolved here's common metadata, so it is a
 * JVM-side declaration that `commonMain` cannot name. That task is the only one that
 * sees it — `desktopTest` and `compileAndroidMain` both pass with the default, and the
 * desktop test is the one that *runs* the file. Every other okio-backed store in this
 * repository already supplies `SYSTEM` at a composition root (`:app`'s
 * `FileBackedPendingMutationStore` and `LibraryDiskCacheStore`), and this now matches.
 *
 * **The file name and the directory name are stored-format contracts, so both stayed
 * exactly as they were.** The directory is [CACHE_DIRECTORY_NAME] under whatever root the
 * caller passes — Android passes `filesDir` — and the file is the cache key's SHA-256 in
 * hex plus `.json`. The name is the only thing connecting an entry on disk to the lookup
 * that finds it, so a digest that is merely *equivalent* orphans every cached home feed
 * on every installed device the moment the app updates. The old code spelled it out by
 * hand (`MessageDigest.getInstance("SHA-256")` and a loop appending
 * `(b ushr 4 and 0x0F).toString(16)`) and this is okio's `ByteString.sha256().hex()`;
 * `:app`'s `libraryCacheFileName` made exactly this swap earlier and
 * `LibraryDiskCacheFileNameTest` measured the two implementations against seven inputs
 * including the empty key and a non-ASCII key, so `CacheFileNameTest` carries `:home`'s
 * own goldens rather than leaning on that measurement by association.
 */
class RecommendationCatalogDiskCacheStore(
    cacheRoot: Path,
    private val timeSource: TimeSource,
    private val ioDispatcher: CoroutineDispatcher,
    private val fileSystem: FileSystem,
) {
    private val cacheDirectory = cacheRoot.resolve(CACHE_DIRECTORY_NAME)

    data class CachedPayload(
        val payload: String,
        val timestampMs: Long,
    ) {
        /** [nowMs] is required. A defaulting `System.currentTimeMillis()` here would be
         * invisible at every call site and untestable; `:android:player` shipped exactly
         * that once and the Kotlin/Native compile gate is what caught it. */
        fun ageMs(nowMs: Long): Long {
            return (nowMs - timestampMs).coerceAtLeast(0L)
        }
    }

    /**
     * The five `null` answers are five, and they stay five: nothing stored, an unreadable
     * file, unparseable JSON, a blank payload, and `timestamp_ms <= 0`. The sixth case,
     * older than [maxAgeMs], is the only one that is not an error. [CachedPayload.ageMs]
     * also coerces a negative age to zero, so a file written by a clock later than the one
     * reading it is treated as fresh rather than as infinitely old — that is the old
     * behaviour and it is the right one, because a user whose clock moves forward should
     * not have every cached feed invalidated at once.
     */
    suspend fun read(cacheKey: String, maxAgeMs: Long? = null): CachedPayload? = withContext(ioDispatcher) {
        val raw = runCatching { readText(cacheFile(cacheKey)) }.getOrNull() ?: return@withContext null
        val json = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return@withContext null
        val payload = json.optStringOrEmpty("payload").trim()
        // `optLong("timestamp_ms", 0L)` was a *defaulting* read, and the default is
        // load-bearing rather than cosmetic: an absent timestamp and a zero timestamp both
        // mean "not a cache entry", and the `> 0` test below is what says so. So the
        // portable form is `optLongOrNull(key) ?: 0`, never `?: nowMs` and never `?: 1L`.
        val timestampMs = json.optLongOrDefault("timestamp_ms", 0L)
        if (payload.isBlank() || timestampMs <= 0L) {
            return@withContext null
        }
        val entry = CachedPayload(payload = payload, timestampMs = timestampMs)
        val limit = maxAgeMs?.takeIf { it > 0L }
        val age = entry.ageMs(timeSource.nowMs())
        return@withContext if (limit != null && age > limit) null else entry
    }

    suspend fun write(cacheKey: String, payload: String) = withContext(ioDispatcher) {
        val timestampMs = timeSource.nowMs()
        val normalizedPayload = payload.trim()
        // An empty payload is not written rather than written and then rejected on read.
        // The two differ only in bytes on disk, and skipping it keeps a caller that
        // serialises an empty snapshot from leaving a file that can never be read.
        if (normalizedPayload.isEmpty()) {
            return@withContext
        }
        val json =
            buildJsonObject {
                put("timestamp_ms", timestampMs)
                put("payload", normalizedPayload)
            }.toString()
        runCatching {
            // The constructor used to `mkdirs()` this directory as a side effect of being
            // constructed. That side effect was never what made `read` work — a missing
            // file already answered `null` through the `runCatching` above — so dropping it
            // changes nothing a caller can observe, and the one place that needs the
            // directory to exist is the one place that writes. `createDirectories` throws
            // where `mkdirs()` returned a boolean, which is why it sits inside the
            // `runCatching` rather than beside it.
            fileSystem.createDirectories(cacheDirectory)
            fileSystem.sink(cacheFile(cacheKey)).buffer().use { it.writeUtf8(json) }
            // `use` answers its block's value, so without this the `runCatching` would be a
            // `Result<BufferedSink>` and the call would not compile as a statement.
            Unit
        }
    }

    private fun cacheFile(cacheKey: String): Path = cacheDirectory.resolve(cacheFileName(cacheKey))

    private fun readText(file: Path): String = fileSystem.source(file).buffer().use { it.readUtf8() }

    private companion object {
        private const val CACHE_DIRECTORY_NAME = "recommendation_catalog_cache"
    }
}

/**
 * The cache file's name for one cache key: its SHA-256, hex, plus `.json`.
 *
 * Top-level and `internal` rather than a `private` member for the usual reason — a
 * decision no test can call is a decision no test can cover — and this is the one decision
 * in the file whose failure mode is invisible: a different digest orphans every cached
 * home feed on every installed device, and nothing in the running app says so.
 */
internal fun cacheFileName(cacheKey: String): String = "${cacheKey.encodeUtf8().sha256().hex()}.json"

/**
 * `optLong(key, default)`, reproducing AOSP rather than adopting `longOrNull`.
 *
 * AOSP answers `Long.parseLong` for a *String* — strict, no exponent and no fraction —
 * and a truncating cast for a *Number*. `longOrNull` parses either, so a port using it
 * answers `null` for `"1e3"` where the old code answered the default; a lenient accessor
 * feeding a strict parser is a truncation, not a parse. `contentOrNull` alone cannot
 * express the difference either: it answers `"1234"` for the number `1234` and for the
 * quoted string `"1234"` alike, and only `isString` separates them.
 */
private fun JsonObject.optLongOrDefault(key: String, default: Long): Long {
    val primitive = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull } ?: return default
    val content = primitive.contentOrNull?.trim() ?: return default
    return if (primitive.isString) {
        content.toLongOrNull() ?: default
    } else {
        content.toDoubleOrNull()?.toLong() ?: default
    }
}
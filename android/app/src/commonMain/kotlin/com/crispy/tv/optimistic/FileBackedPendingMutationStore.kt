package com.crispy.tv.optimistic


import com.crispy.tv.domain.optimistic.EpisodeWatchedMutation
import com.crispy.tv.domain.optimistic.MediaContentType
import com.crispy.tv.domain.optimistic.MutationKind
import com.crispy.tv.domain.optimistic.MutationStatus
import com.crispy.tv.domain.optimistic.RatingMutation
import com.crispy.tv.domain.optimistic.SeasonWatchedMutation
import com.crispy.tv.domain.optimistic.TitleWatchedMutation
import com.crispy.tv.domain.optimistic.UserMutation
import com.crispy.tv.domain.optimistic.WatchlistMutation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import com.crispy.tv.library.jsonPrimitiveOrNull
import com.crispy.tv.library.optBooleanOrNull
import com.crispy.tv.library.optBooleanOrThrow
import com.crispy.tv.library.optIntOrNull
import com.crispy.tv.library.optLongOrNull
import com.crispy.tv.library.optStringOrEmpty
import com.crispy.tv.library.optStringOrNull
import com.crispy.tv.library.optStringOrThrow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path
import okio.buffer

/**
 * The [PendingMutationStore] implementation that persists to a single JSON file.
 *
 * **This is the third reason that was listed here to be discharged, and it is the
 * last one.** The file used to be `androidMain` for `org.json` (a class of the
 * Android platform supplied by `android.jar`, not a dependency of this project),
 * then for `java.io.File` and `Dispatchers.IO` on top of that. `org.json` went
 * first; this landing takes both of the others, and the file is now `commonMain`.
 *
 * **The filesystem is a constructor parameter rather than a default, and the
 * dispatcher is too, and neither fact is a style choice.** `Dispatchers.IO` is
 * `public` on the JVM and `internal` on Kotlin/Native, so a `commonMain` file that
 * named it would not compile for a native target -- and a *defaulted* dispatcher
 * would compile everywhere while silently putting blocking file IO on a
 * CPU-sized pool, which is the failure mode the parameter exists to prevent. The
 * same reasoning applies to the [FileSystem]: injecting it is what lets a
 * `commonTest` drive the store with an in-memory filesystem on any target, which
 * is the coverage the wire format below had never had.
 *
 * **okio is already on this module's `commonMain` classpath, and it is on it
 * because of an image-loading library, not because of this file** -- it arrives
 * through `coil3` (`coil-core` depends on `okio`). So there is no dependency
 * line to add here, and the reason it is worth using is that it is *already*
 * there: a replacement for a JVM library call belongs where the existing
 * replacement already lives, and adding a second filesystem abstraction next to
 * it would be the layering this project does not do.
 */
internal class FileBackedPendingMutationStore(
    private val fileSystem: FileSystem,
    private val path: Path,
    private val ioDispatcher: CoroutineDispatcher,
) : PendingMutationStore {
    override suspend fun loadAll(): List<UserMutation> =
        withContext(ioDispatcher) {
            // okio's `read` would answer the same question in one call, but it
            // does not exist in the version resolved here -- `FileSystem` has
            // `source`/`sink` and no string overloads -- so the absent-file check
            // is a separate `metadataOrNull`. The old code asked `File.exists()`,
            // which is also a metadata question, so this is one call moved rather
            // than a new guard: an unreadable file still falls through to the
            // `runCatching` below and still answers `emptyList()`.
            if (fileSystem.metadataOrNull(path) == null) return@withContext emptyList()
            runCatching {
                val array = Json.parseToJsonElement(fileSystem.source(path).buffer().use { it.readUtf8() }).jsonArray
                array.mapNotNull { decode(it as? JsonObject) }
            }.getOrDefault(emptyList())
        }

    override suspend fun saveAll(mutations: List<UserMutation>): Unit =
        withContext(ioDispatcher) {
            val array = buildJsonArray { mutations.forEach { add(encode(it)) } }
            // The old code called `file.parentFile?.mkdirs()` and ignored the
            // result, then wrote. `Path.parent` is nullable for the same reason
            // `File.parentFile` was -- a bare relative filename has no parent --
            // so the null check is the existing guard, not a new one. The
            // directory creation moved *inside* the `runCatching` because
            // okio's `createDirectories` throws where `mkdirs()` returned a
            // boolean: leaving it outside would turn a swallowed write failure
            // into an escaping exception, which is a behaviour change in the
            // direction of crashing the caller.
            runCatching {
                path.parent?.let { fileSystem.createDirectories(it) }
                // **`use`, and not `flush`.** This is the one line the first
                // version of this port got wrong, and the in-memory filesystem in
                // `FileBackedPendingMutationStoreTest` is what caught it: okio
                // buffers, and a flushed-but-unclosed sink has not been committed,
                // so a read on the same path saw the *previous* contents and a
                // second write failed with "file is already open for writing".
                // Seventeen of twenty-one tests failed at once, which is the
                // signature of a missing `close` rather than of a bad fixture.
                // `java.io.File.writeText` closed implicitly, so nothing in the old
                // code said "this has to be closed" -- the port is what made the
                // obligation explicit, and `flush` reads like it discharges it.
                fileSystem.sink(path).buffer().use { it.writeUtf8(array.toString()) }
            }
        }

    private fun encode(mutation: UserMutation): JsonObject {
        return buildJsonObject {
            put("type", mutation.kind.name)
            put("id", mutation.id)
            put("titleItemId", mutation.titleItemId)
            put("entityId", mutation.entityId)
            put("createdAtMs", mutation.createdAtMs)
            put("attempt", mutation.attempt)
            put("nextAttemptAtMs", mutation.nextAttemptAtMs)
            when (val s = mutation.status) {
                MutationStatus.Pending -> put("status", "pending")
                MutationStatus.Inflight -> put("status", "inflight")
                is MutationStatus.Failed -> {
                    put("status", "failed")
                    put("statusReason", s.reason)
                    put("retryable", s.retryable)
                }
                is MutationStatus.Conflict -> {
                    put("status", "conflict")
                    put("statusServerValue", s.serverValue)
                }
            }
            when (mutation) {
                is WatchlistMutation -> put("desired", mutation.desired)
                is TitleWatchedMutation -> {
                    put("contentType", mutation.contentType.name)
                    put("desired", mutation.desired)
                }
                is RatingMutation -> {
                    if (mutation.desired == null) put("desired", JsonNull) else put("desired", mutation.desired)
                }
                is EpisodeWatchedMutation -> {
                    put("itemId", mutation.itemId)
                    put("season", mutation.season)
                    if (mutation.episode != null) put("episode", mutation.episode)
                    put("videoId", mutation.videoId)
                    put("desired", mutation.desired)
                }
                is SeasonWatchedMutation -> {
                    put("seasonItemId", mutation.seasonItemId)
                    put("seasonNumber", mutation.seasonNumber)
                    put("desired", mutation.desired)
                }
            }
        }
    }

    private fun decode(obj: JsonObject?): UserMutation? {
        if (obj == null) return null
        val kind =
            try {
                MutationKind.valueOf(obj.optStringOrThrow("type"))
            } catch (_: Exception) {
                return null
            }
        val id = obj.optStringOrEmpty("id")
        val titleItemId = obj.optStringOrEmpty("titleItemId")
        val entityId = obj.optStringOrEmpty("entityId")
        val createdAtMs = obj.optLongOrNull("createdAtMs") ?: 0
        val attempt = obj.optIntOrNull("attempt") ?: 0
        val nextAttemptAtMs = obj.optLongOrNull("nextAttemptAtMs") ?: 0
        val status = decodeStatus(obj)

        return when (kind) {
            MutationKind.WATCHLIST ->
                WatchlistMutation(id, titleItemId, entityId, createdAtMs, attempt, status, nextAttemptAtMs, obj.optBooleanOrThrow("desired"))
            MutationKind.TITLE_WATCHED -> {
                val contentType =
                    try {
                        // `optString(key, "MOVIE")` returns the default only when
                        // the key is ABSENT; a stored `""` came through as `""` and
                        // then threw into the catch below. `ifBlank` folds that
                        // second case into the same answer one line earlier, so
                        // the outcome is identical and only the path differs.
                        MediaContentType.valueOf(obj.optStringOrEmpty("contentType").ifBlank { "MOVIE" })
                    } catch (_: Exception) {
                        MediaContentType.MOVIE
                    }
                TitleWatchedMutation(id, titleItemId, entityId, createdAtMs, attempt, status, nextAttemptAtMs, contentType, obj.optBooleanOrThrow("desired"))
            }
            MutationKind.RATING ->
                RatingMutation(id, titleItemId, entityId, createdAtMs, attempt, status, nextAttemptAtMs, desiredFrom(obj))
            MutationKind.EPISODE_WATCHED ->
                EpisodeWatchedMutation(
                    id,
                    titleItemId,
                    entityId,
                    createdAtMs,
                    attempt,
                    status,
                    nextAttemptAtMs,
                    itemId = obj.optStringOrEmpty("itemId"),
                    season = obj.optIntOrNull("season") ?: 0,
                    // **The `has("episode")` guard disappears into the accessor,
                    // and this is the one place in the file where that is the
                    // point rather than a convenience.** `0` is a legitimate
                    // episode number, so the guard was load-bearing: a defaulted
                    // `optInt` could not tell "absent" from "episode zero". A
                    // nullable read can, which is why `optIntOrNull` exists at
                    // all -- the two answers differ on exactly one input and
                    // that input is the one the field allows.
                    episode = obj.optIntOrNull("episode"),
                    videoId = obj.optStringOrEmpty("videoId"),
                    desired = obj.optBooleanOrThrow("desired"),
                )
            MutationKind.SEASON_WATCHED ->
                SeasonWatchedMutation(
                    id,
                    titleItemId,
                    entityId,
                    createdAtMs,
                    attempt,
                    status,
                    nextAttemptAtMs,
                    seasonItemId = obj.optStringOrEmpty("seasonItemId"),
                    seasonNumber = obj.optIntOrNull("seasonNumber") ?: 0,
                    desired = obj.optBooleanOrThrow("desired"),
                )
        }
    }

    // `isNull(key) ? null : optBoolean(key)` became one nullable read. The one
    // input where the answers differ is a key present with a NON-boolean, which
    // used to read `false` and now reads `null`: `encode` above writes a real
    // `Boolean` or `JsonNull` and nothing else, so the input cannot arise in a
    // file this class wrote.
    private fun desiredFrom(obj: JsonObject): Boolean? = obj.optBooleanOrNull("desired")

    private fun decodeStatus(obj: JsonObject): MutationStatus =
        when (obj.optStringOrEmpty("status")) {
            "pending" -> MutationStatus.Pending
            "inflight" -> MutationStatus.Pending
            "failed" ->
                MutationStatus.Failed(
                    reason = obj.optStringOrEmpty("statusReason"),
                    retryable = obj.optBooleanOrNull("retryable") ?: true,
                )
            "conflict" -> MutationStatus.Conflict(serverValue = obj.optStringOrNull("statusServerValue"))
            else -> MutationStatus.Pending
        }
}

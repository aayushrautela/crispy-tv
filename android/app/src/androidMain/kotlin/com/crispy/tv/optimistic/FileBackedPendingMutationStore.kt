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
import kotlinx.coroutines.Dispatchers
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
import java.io.File

/**
 * The [PendingMutationStore] implementation that persists to a single JSON file.
 *
 * **It was `androidMain` for three reasons, and this port deletes one of them.**
 * It reached for `org.json`, which is a class of the Android platform supplied by
 * `android.jar` rather than a dependency of this project. The other two are
 * `java.io.File` and `Dispatchers.IO` (which is `public` on the JVM and `internal`
 * on Kotlin/Native), and **both would remain if the JSON went away tomorrow** --
 * so the file stays where it is and the original claim that `org.json` was why is
 * now simply gone, because keeping it would invite a reader to re-derive the
 * file's placement from a reason that stopped being true.
 */
internal class FileBackedPendingMutationStore(
    private val file: File,
) : PendingMutationStore {
    override suspend fun loadAll(): List<UserMutation> =
        withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext emptyList()
            runCatching {
                val array = Json.parseToJsonElement(file.readText()).jsonArray
                array.mapNotNull { decode(it as? JsonObject) }
            }.getOrDefault(emptyList())
        }

    override suspend fun saveAll(mutations: List<UserMutation>): Unit =
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val array = buildJsonArray { mutations.forEach { add(encode(it)) } }
            runCatching { file.writeText(array.toString()) }
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

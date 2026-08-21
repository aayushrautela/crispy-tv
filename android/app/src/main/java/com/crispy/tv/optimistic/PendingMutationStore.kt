package com.crispy.tv.optimistic

import com.crispy.tv.domain.optimistic.EpisodeWatchedMutation
import com.crispy.tv.domain.optimistic.MutationKind
import com.crispy.tv.domain.optimistic.MutationStatus
import com.crispy.tv.domain.optimistic.RatingMutation
import com.crispy.tv.domain.optimistic.SeasonWatchedMutation
import com.crispy.tv.domain.optimistic.TitleWatchedMutation
import com.crispy.tv.domain.optimistic.UserMutation
import com.crispy.tv.domain.optimistic.WatchlistMutation
import com.crispy.tv.player.MetadataLabMediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Durable, process-death-safe store for pending mutations. Backed by a single
 * JSON file; loading coerces any transient [MutationStatus.Inflight] entry back
 * to [MutationStatus.Pending] so a write interrupted by a crash is retried.
 */
interface PendingMutationStore {
    suspend fun loadAll(): List<UserMutation>

    suspend fun saveAll(mutations: List<UserMutation>)
}

internal class FileBackedPendingMutationStore(
    private val file: File,
) : PendingMutationStore {
    override suspend fun loadAll(): List<UserMutation> =
        withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext emptyList()
            runCatching {
                val array = JSONArray(file.readText())
                (0 until array.length()).mapNotNull { decode(array.optJSONObject(it)) }
            }.getOrDefault(emptyList())
        }

    override suspend fun saveAll(mutations: List<UserMutation>): Unit =
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val array = JSONArray()
            mutations.forEach { array.put(encode(it)) }
            runCatching { file.writeText(array.toString()) }
        }

    private fun encode(mutation: UserMutation): JSONObject {
        val obj =
            JSONObject().apply {
                put("type", mutation.kind.name)
                put("id", mutation.id)
                put("titleItemId", mutation.titleItemId)
                put("entityId", mutation.entityId)
                put("createdAtMs", mutation.createdAtMs)
                put("attempt", mutation.attempt)
                put("nextAttemptAtMs", mutation.nextAttemptAtMs)
                when (mutation.status) {
                    MutationStatus.Pending -> put("status", "pending")
                    MutationStatus.Inflight -> put("status", "inflight")
                    is MutationStatus.Failed -> {
                        put("status", "failed")
                        put("statusReason", mutation.status.reason)
                        put("retryable", mutation.status.retryable)
                    }
                    is MutationStatus.Conflict -> {
                        put("status", "conflict")
                        put("statusServerValue", mutation.status.serverValue)
                    }
                }
            }
        when (mutation) {
            is WatchlistMutation -> obj.put("desired", mutation.desired)
            is TitleWatchedMutation -> {
                obj.put("contentType", mutation.contentType.name)
                obj.put("desired", mutation.desired)
            }
            is RatingMutation -> {
                if (mutation.desired == null) obj.put("desired", JSONObject.NULL) else obj.put("desired", mutation.desired)
            }
            is EpisodeWatchedMutation -> {
                obj.put("itemId", mutation.itemId)
                obj.put("season", mutation.season)
                if (mutation.episode != null) obj.put("episode", mutation.episode)
                obj.put("videoId", mutation.videoId)
                obj.put("desired", mutation.desired)
            }
            is SeasonWatchedMutation -> {
                obj.put("seasonItemId", mutation.seasonItemId)
                obj.put("seasonNumber", mutation.seasonNumber)
                obj.put("desired", mutation.desired)
            }
        }
        return obj
    }

    private fun decode(obj: JSONObject?): UserMutation? {
        if (obj == null) return null
        val kind =
            try {
                MutationKind.valueOf(obj.getString("type"))
            } catch (_: Exception) {
                return null
            }
        val id = obj.optString("id")
        val titleItemId = obj.optString("titleItemId")
        val entityId = obj.optString("entityId")
        val createdAtMs = obj.optLong("createdAtMs")
        val attempt = obj.optInt("attempt")
        val nextAttemptAtMs = obj.optLong("nextAttemptAtMs")
        val status = decodeStatus(obj)

        return when (kind) {
            MutationKind.WATCHLIST ->
                WatchlistMutation(id, titleItemId, entityId, createdAtMs, attempt, status, nextAttemptAtMs, obj.getBoolean("desired"))
            MutationKind.TITLE_WATCHED -> {
                val contentType =
                    try {
                        MetadataLabMediaType.valueOf(obj.optString("contentType", "MOVIE"))
                    } catch (_: Exception) {
                        MetadataLabMediaType.MOVIE
                    }
                TitleWatchedMutation(id, titleItemId, entityId, createdAtMs, attempt, status, nextAttemptAtMs, contentType, obj.getBoolean("desired"))
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
                    itemId = obj.optString("itemId"),
                    season = obj.optInt("season"),
                    episode = if (obj.has("episode")) obj.optInt("episode") else null,
                    videoId = obj.optString("videoId"),
                    desired = obj.getBoolean("desired"),
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
                    seasonItemId = obj.optString("seasonItemId"),
                    seasonNumber = obj.optInt("seasonNumber"),
                    desired = obj.getBoolean("desired"),
                )
        }
    }

    private fun desiredFrom(obj: JSONObject): Int? =
        if (obj.isNull("desired")) null else obj.optInt("desired")

    private fun decodeStatus(obj: JSONObject): MutationStatus =
        when (obj.optString("status")) {
            "pending" -> MutationStatus.Pending
            "inflight" -> MutationStatus.Pending
            "failed" ->
                MutationStatus.Failed(
                    reason = obj.optString("statusReason", ""),
                    retryable = obj.optBoolean("retryable", true),
                )
            "conflict" -> MutationStatus.Conflict(serverValue = if (obj.isNull("statusServerValue")) null else obj.optString("statusServerValue"))
            else -> MutationStatus.Pending
        }
}

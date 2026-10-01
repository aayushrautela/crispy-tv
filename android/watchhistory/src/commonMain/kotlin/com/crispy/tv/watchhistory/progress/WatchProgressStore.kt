package com.crispy.tv.watchhistory.progress

import com.crispy.tv.platform.AppLogger
import com.crispy.tv.platform.KeyValueStore
import com.crispy.tv.platform.MonotonicClock
import com.crispy.tv.platform.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.max

data class WatchProgress(
    val currentTimeSeconds: Double,
    val durationSeconds: Double,
    val lastUpdatedEpochMs: Long,
    val remoteImdbId: String? = null,
    val addonId: String? = null,
) {
    fun progressPercentOrZero(): Double {
        val duration = durationSeconds
        if (duration <= 0.0) return 0.0
        return (currentTimeSeconds / duration) * 100.0
    }
}

class WatchProgressStore(
    private val store: KeyValueStore,
    private val timeSource: TimeSource,
    private val monotonicClock: MonotonicClock,
    private val logger: AppLogger,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private var notificationJob: Job? = null
    private var lastNotificationAtElapsedMs: Long = 0L
    private val updatesFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val removalsFlow = MutableSharedFlow<RemovalEvent>(extraBufferCapacity = 1)

    private var cache: Map<String, WatchProgress>? = null
    private var cacheAtEpochMs: Long = 0L

    val updates: SharedFlow<Unit> = updatesFlow
    val removals: SharedFlow<RemovalEvent> = removalsFlow

    data class RemovalEvent(
        val id: String,
        val type: String,
        val episodeId: String?,
    )

    data class SetOptions(
        val forceNotify: Boolean = false,
    )

    fun setContentDuration(id: String, type: String, durationSeconds: Double, episodeId: String? = null) {
        store.putString(getContentDurationPrefKey(id = id, type = type, episodeId = episodeId), durationSeconds.toString())
    }

    fun getContentDurationSeconds(id: String, type: String, episodeId: String? = null): Double? {
        return store.getString(getContentDurationPrefKey(id = id, type = type, episodeId = episodeId), null)
            ?.trim()
            ?.toDoubleOrNull()
    }

    fun updateProgressDuration(id: String, type: String, newDurationSeconds: Double, episodeId: String? = null) {
        val existing = getWatchProgress(id = id, type = type, episodeId = episodeId) ?: return
        if (abs(existing.durationSeconds - newDurationSeconds) <= DURATION_UPDATE_THRESHOLD_SECONDS) return

        val percent = existing.progressPercentOrZero()
        val newCurrentTime = (percent / 100.0) * newDurationSeconds
        val updated = existing.copy(
            currentTimeSeconds = newCurrentTime,
            durationSeconds = newDurationSeconds,
            lastUpdatedEpochMs = timeSource.nowMs(),
        )
        setWatchProgress(id = id, type = type, progress = updated, episodeId = episodeId)
    }

    fun addWatchProgressTombstone(id: String, type: String, episodeId: String? = null, deletedAtEpochMs: Long? = null) {
        val tombstones = getWatchProgressTombstones().toMutableMap()
        val key = buildWpKeyString(id = id, type = type, episodeId = episodeId)
        tombstones[key] = deletedAtEpochMs ?: timeSource.nowMs()
        writeTombstones(tombstones)
    }

    fun clearWatchProgressTombstone(id: String, type: String, episodeId: String? = null) {
        val tombstones = getWatchProgressTombstones().toMutableMap()
        val key = buildWpKeyString(id = id, type = type, episodeId = episodeId)
        if (tombstones.remove(key) != null) {
            writeTombstones(tombstones)
        }
    }

    fun getWatchProgressTombstones(): Map<String, Long> = readLongMap(WP_TOMBSTONES_KEY)

    fun addContinueWatchingRemoved(id: String, type: String, removedAtEpochMs: Long? = null) {
        val removed = getContinueWatchingRemoved().toMutableMap()
        removed[buildWpKeyString(id = id, type = type)] = removedAtEpochMs ?: timeSource.nowMs()
        writeContinueWatchingRemoved(removed)
    }

    fun removeContinueWatchingRemoved(id: String, type: String) {
        val removed = getContinueWatchingRemoved().toMutableMap()
        if (removed.remove(buildWpKeyString(id = id, type = type)) != null) {
            writeContinueWatchingRemoved(removed)
        }
    }

    fun getContinueWatchingRemoved(): Map<String, Long> = readLongMap(CONTINUE_WATCHING_REMOVED_KEY)

    fun isContinueWatchingRemoved(id: String, type: String): Boolean {
        val removed = getContinueWatchingRemoved()
        return removed.containsKey(buildWpKeyString(id = id, type = type))
    }

    fun setWatchProgress(id: String, type: String, progress: WatchProgress, episodeId: String? = null, options: SetOptions = SetOptions()) {
        val tombstones = getWatchProgressTombstones()
        val exactKey = buildWpKeyString(id = id, type = type, episodeId = episodeId)
        val baseKey = buildWpKeyString(id = id, type = type)

        val newestTombAt = max(tombstones[exactKey] ?: Long.MIN_VALUE, tombstones[baseKey] ?: Long.MIN_VALUE)
            .takeIf { it != Long.MIN_VALUE }
        if (newestTombAt != null) {
            val lastUpdated = progress.lastUpdatedEpochMs.takeIf { it > 0 }
            if (lastUpdated == null || lastUpdated <= newestTombAt) {
                return
            }
        }

        // Reporting cadence/floor is enforced upstream (PlayerSessionViewModel +
        // BackendWatchHistoryService), so every accepted call is a meaningful write.
        val timestamp = timeSource.nowMs()

        maybeRestoreContinueWatchingVisibility(id = id, type = type, episodeId = episodeId, timestampEpochMs = timestamp)

        val updated = progress.copy(lastUpdatedEpochMs = timestamp)
        val prefKey = getWatchProgressPrefKey(id = id, type = type, episodeId = episodeId)
        store.putString(prefKey, updated.toJson().toString())
        invalidateCache()

        if (options.forceNotify) {
            notifyNow()
        } else {
            debouncedNotify()
        }
    }

    fun getWatchProgress(id: String, type: String, episodeId: String? = null): WatchProgress? {
        val raw = store.getString(getWatchProgressPrefKey(id = id, type = type, episodeId = episodeId), null) ?: return null
        return try {
            WatchProgressJson.fromJson(Json.parseToJsonElement(raw).jsonObject)
        } catch (e: Exception) {
            logger.warn(LOG_TAG, "Failed to parse watch progress JSON", e)
            null
        }
    }

    fun removeWatchProgress(id: String, type: String, episodeId: String? = null) {
        store.remove(getWatchProgressPrefKey(id = id, type = type, episodeId = episodeId))
        addWatchProgressTombstone(id = id, type = type, episodeId = episodeId)
        invalidateCache()
        notifyNow()
        removalsFlow.tryEmit(RemovalEvent(id = id, type = type, episodeId = episodeId))
    }

    fun getAllWatchProgress(): Map<String, WatchProgress> {
        val now = timeSource.nowMs()
        val cached = cache
        if (cached != null && now - cacheAtEpochMs < WATCH_PROGRESS_CACHE_TTL_MS) {
            return cached
        }

        val result = LinkedHashMap<String, WatchProgress>()
        // Sorted, because `keys()` carries no order guarantee and this map is
        // consumed by the continue-watching planner. The old code iterated
        // `prefs.all`, a `HashMap`, so the order it produced was arbitrary; sorting
        // makes the result deterministic rather than merely different per platform.
        for (key in store.keys().sorted()) {
            if (!key.startsWith(WATCH_PROGRESS_KEY_PREFIX)) continue
            // `getString` rather than reading a snapshot value: the port dropped
            // `prefs.all`'s untyped map, and a key that somehow holds a non-string
            // must be skipped exactly as the old `as? String ?: continue` did.
            val raw = runCatching { store.getString(key) }.getOrNull() ?: continue
            val stripped = key.removePrefix(WATCH_PROGRESS_KEY_PREFIX)
            val parsed =
                runCatching { WatchProgressJson.fromJson(Json.parseToJsonElement(raw).jsonObject) }
                    .getOrNull() ?: continue
            result[stripped] = parsed
        }

        cache = result
        cacheAtEpochMs = now
        return result
    }

    fun removeAllWatchProgressForContent(id: String, type: String, addBaseTombstone: Boolean) {
        val all = getAllWatchProgress()
        val prefix = "$type:$id"
        val keysToRemove = all.keys.filter { it == prefix || it.startsWith("$prefix:") }
        for (key in keysToRemove) {
            val parts = key.split(':')
            val episodeId = if (parts.size > 2) parts.subList(2, parts.size).joinToString(":") else null
            removeWatchProgress(id = id, type = type, episodeId = episodeId)
        }
        if (addBaseTombstone) {
            addWatchProgressTombstone(id = id, type = type, episodeId = null)
        }
    }

    private fun maybeRestoreContinueWatchingVisibility(id: String, type: String, episodeId: String?, timestampEpochMs: Long) {
        val removed = getContinueWatchingRemoved()

        data class Candidate(val removeId: String, val key: String)

        val candidates = buildList {
            val baseId = id.trim()
            if (baseId.isNotBlank()) {
                add(Candidate(removeId = baseId, key = buildWpKeyString(id = baseId, type = type)))
            }

            if (!episodeId.isNullOrBlank()) {
                val normalized = normalizeContinueWatchingEpisodeRemoveId(id = baseId, episodeId = episodeId.trim())
                if (normalized.isNotBlank()) {
                    add(Candidate(removeId = normalized, key = buildWpKeyString(id = normalized, type = type)))
                }
            }
        }

        for (candidate in candidates) {
            val removedAt = removed[candidate.key] ?: continue
            if (timestampEpochMs > removedAt) {
                removeContinueWatchingRemoved(id = candidate.removeId, type = type)
            }
        }
    }

    private fun writeTombstones(map: Map<String, Long>) = writeLongMap(WP_TOMBSTONES_KEY, map)

    private fun writeContinueWatchingRemoved(map: Map<String, Long>) =
        writeLongMap(CONTINUE_WATCHING_REMOVED_KEY, map)

    /**
     * The two readers above were byte-identical apart from the key constant, and
     * so were the two writers, so `org.json` was paying for four copies of two
     * bodies. The node type change is what made the extraction possible rather
     * than gratuitous: a `JSONObject` is constructed inline in each copy, so the
     * copies could not share a helper without the helper taking the node as a
     * parameter, which would have put the parse in the caller four times over.
     *
     * **The reader no longer needs a sentinel, and that is the one behaviour
     * change here worth reading.** The old reader used `optLong(key, MIN)` as its
     * accept filter, so a stored `Long.MIN_VALUE` could not be told from a
     * non-numeric entry and was dropped. `jsonPrimitive.longOrNull` is `null` for
     * a non-number and the real value for `Long.MIN_VALUE`, which *is* a valid
     * long, so the reader keeps it and drops only what it cannot read.
     *
     * **The masking did not vanish, it moved.** `setWatchProgress` still uses
     * `Long.MIN_VALUE` as a sentinel — `max(exact ?: MIN, base ?: MIN).takeIf
     * { it != MIN }` — and that code reads a `Map<String, Long>` rather than
     * JSON, so the migration did not touch it. A genuine `Long.MIN_VALUE`
     * tombstone is therefore now read and then treated by the gate as "no
     * tombstone at all", so the write goes through. Benign, because timestamps
     * are `nowMs()` and no caller can produce the value, and pinned by
     * `WatchProgressStoreHostTest` so it stays a decision rather than an
     * accident — **two sentinels on opposite sides of one value is the shape to
     * watch for when a type change removes one of them.**
     */
    private fun readLongMap(key: String): Map<String, Long> {
        val raw = store.getString(key, null) ?: return emptyMap()
        val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyMap()
        val result = LinkedHashMap<String, Long>(obj.size)
        for ((entryKey, element) in obj) {
            val value = element.jsonPrimitive.longOrNull
            if (value != null) {
                result[entryKey] = value
            }
        }
        return result
    }

    private fun writeLongMap(key: String, map: Map<String, Long>) {
        val obj = JsonObject(map.mapValues { (_, v) -> JsonPrimitive(v) })
        store.putString(key, obj.toString())
    }

    private fun invalidateCache() {
        cache = null
        cacheAtEpochMs = 0L
    }

    private fun debouncedNotify() {
        notificationJob?.cancel()

        val nowElapsedMs = monotonicClock.elapsedMs()
        val since = nowElapsedMs - lastNotificationAtElapsedMs
        if (since < MIN_NOTIFICATION_INTERVAL_MS) {
            notificationJob =
                scope.launch {
                    delay(NOTIFICATION_DEBOUNCE_MS)
                    notifyNow()
                }
            return
        }

        notifyNow()
    }

    private fun notifyNow() {
        notificationJob?.cancel()
        lastNotificationAtElapsedMs = monotonicClock.elapsedMs()
        updatesFlow.tryEmit(Unit)
    }

    private object WatchProgressJson {
        fun fromJson(obj: JsonObject): WatchProgress {
            return WatchProgress(
                currentTimeSeconds = obj.doubleOrZero("currentTime"),
                durationSeconds = obj.doubleOrZero("duration"),
                lastUpdatedEpochMs = obj.longOrZero("lastUpdated"),
                remoteImdbId = obj.trimmedOrNull("remoteImdbId"),
                addonId = obj.trimmedOrNull("addonId"),
            )
        }

        private fun JsonObject.doubleOrZero(key: String): Double =
            this[key]?.jsonPrimitive?.doubleOrNull ?: 0.0

        private fun JsonObject.longOrZero(key: String): Long =
            this[key]?.jsonPrimitive?.longOrNull ?: 0L

        /**
         * **This is where the migration is not mechanical, and the difference is
         * deliberate.** `org.json`'s `optString` of a JSON null returns the four
         * characters `"null"` on the platform (measured in `e27619cb`: the Maven
         * artifact returns `""` and the AOSP one `"null"`), and `.trim().ifBlank
         * { null }` does not blank that — so a stored JSON null used to round-trip
         * as the string `"null"`, and `WatchProgressStoreHostTest` pinned it.
         *
         * Under `JsonElement` a JSON null is `JsonNull`, whose `contentOrNull` is
         * `null`, so the value is now genuinely null. **That is the fix, not a
         * regression:** the old answer was the platform's rendering of a null
         * leaking through a string accessor, and a remote id of `"null"` reaching
         * the backend is worse than a missing one. The test that pinned the old
         * behaviour was changed to pin the new one, and the change is recorded
         * here and in `AGENTS.md` rather than left to a test diff.
         *
         * A **non-string** primitive still goes through `contentOrNull` and is
         * trimmed the same way, so a numeric `remoteImdbId` keeps its old
         * stringification rather than silently becoming null.
         */
        private fun JsonObject.trimmedOrNull(key: String): String? =
            this[key]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
    }

    private fun WatchProgress.toJson(): JsonObject = buildJsonObject {
        put("currentTime", currentTimeSeconds)
        put("duration", durationSeconds)
        put("lastUpdated", lastUpdatedEpochMs)
        if (!remoteImdbId.isNullOrBlank()) put("remoteImdbId", remoteImdbId)
        if (!addonId.isNullOrBlank()) put("addonId", addonId)
    }

    private fun normalizedImdbIdOrNull(raw: String?): String? {
        val value = raw?.trim()?.lowercase().orEmpty()
        if (value.isBlank()) return null

        val candidate =
            when {
                value.startsWith("tt") -> value
                value.startsWith("imdb:") -> value.substringAfter("imdb:")
                value.all { it.isDigit() } -> "tt$value"
                else -> return null
            }

        if (!candidate.startsWith("tt")) return null
        if (candidate.length < 4) return null
        if (!candidate.substring(2).all { it.isDigit() }) return null
        return candidate
    }

    private companion object {
        /** A constant now, rather than the old per-instance `logTag` parameter. */
        private const val LOG_TAG = "WatchProgressStore"

        private const val WP_TOMBSTONES_KEY = "@wp_tombstones"
        private const val CONTINUE_WATCHING_REMOVED_KEY = "@continue_watching_removed"

        private const val WATCH_PROGRESS_CACHE_TTL_MS = 5_000L
        private const val NOTIFICATION_DEBOUNCE_MS = 1_000L
        private const val MIN_NOTIFICATION_INTERVAL_MS = 500L

        private const val DURATION_UPDATE_THRESHOLD_SECONDS = 60.0
    }
}

package com.crispy.tv.watchhistory.progress

import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale
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
    private val prefs: SharedPreferences,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val logTag: String = "WatchProgressStore",
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
        prefs.edit().putString(getContentDurationPrefKey(id = id, type = type, episodeId = episodeId), durationSeconds.toString()).apply()
    }

    fun getContentDurationSeconds(id: String, type: String, episodeId: String? = null): Double? {
        return prefs.getString(getContentDurationPrefKey(id = id, type = type, episodeId = episodeId), null)
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
            lastUpdatedEpochMs = nowEpochMs(),
        )
        setWatchProgress(id = id, type = type, progress = updated, episodeId = episodeId)
    }

    fun addWatchProgressTombstone(id: String, type: String, episodeId: String? = null, deletedAtEpochMs: Long? = null) {
        val tombstones = getWatchProgressTombstones().toMutableMap()
        val key = buildWpKeyString(id = id, type = type, episodeId = episodeId)
        tombstones[key] = deletedAtEpochMs ?: nowEpochMs()
        writeTombstones(tombstones)
    }

    fun clearWatchProgressTombstone(id: String, type: String, episodeId: String? = null) {
        val tombstones = getWatchProgressTombstones().toMutableMap()
        val key = buildWpKeyString(id = id, type = type, episodeId = episodeId)
        if (tombstones.remove(key) != null) {
            writeTombstones(tombstones)
        }
    }

    fun getWatchProgressTombstones(): Map<String, Long> {
        val raw = prefs.getString(WP_TOMBSTONES_KEY, null) ?: return emptyMap()
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val result = LinkedHashMap<String, Long>(obj.length())
        for (key in obj.keys()) {
            val value = obj.optLong(key, Long.MIN_VALUE)
            if (value != Long.MIN_VALUE) {
                result[key] = value
            }
        }
        return result
    }

    fun addContinueWatchingRemoved(id: String, type: String, removedAtEpochMs: Long? = null) {
        val removed = getContinueWatchingRemoved().toMutableMap()
        removed[buildWpKeyString(id = id, type = type)] = removedAtEpochMs ?: nowEpochMs()
        writeContinueWatchingRemoved(removed)
    }

    fun removeContinueWatchingRemoved(id: String, type: String) {
        val removed = getContinueWatchingRemoved().toMutableMap()
        if (removed.remove(buildWpKeyString(id = id, type = type)) != null) {
            writeContinueWatchingRemoved(removed)
        }
    }

    fun getContinueWatchingRemoved(): Map<String, Long> {
        val raw = prefs.getString(CONTINUE_WATCHING_REMOVED_KEY, null) ?: return emptyMap()
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val result = LinkedHashMap<String, Long>(obj.length())
        for (key in obj.keys()) {
            val value = obj.optLong(key, Long.MIN_VALUE)
            if (value != Long.MIN_VALUE) {
                result[key] = value
            }
        }
        return result
    }

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
        val timestamp = nowEpochMs()

        maybeRestoreContinueWatchingVisibility(id = id, type = type, episodeId = episodeId, timestampEpochMs = timestamp)

        val updated = progress.copy(lastUpdatedEpochMs = timestamp)
        val prefKey = getWatchProgressPrefKey(id = id, type = type, episodeId = episodeId)
        prefs.edit().putString(prefKey, updated.toJson().toString()).apply()
        invalidateCache()

        if (options.forceNotify) {
            notifyNow()
        } else {
            debouncedNotify()
        }
    }

    fun getWatchProgress(id: String, type: String, episodeId: String? = null): WatchProgress? {
        val raw = prefs.getString(getWatchProgressPrefKey(id = id, type = type, episodeId = episodeId), null) ?: return null
        return try {
            WatchProgressJson.fromJson(JSONObject(raw))
        } catch (e: JSONException) {
            Log.w(logTag, "Failed to parse watch progress JSON", e)
            null
        }
    }

    fun removeWatchProgress(id: String, type: String, episodeId: String? = null) {
        prefs.edit().remove(getWatchProgressPrefKey(id = id, type = type, episodeId = episodeId)).apply()
        addWatchProgressTombstone(id = id, type = type, episodeId = episodeId)
        invalidateCache()
        notifyNow()
        removalsFlow.tryEmit(RemovalEvent(id = id, type = type, episodeId = episodeId))
    }

    fun getAllWatchProgress(): Map<String, WatchProgress> {
        val now = nowEpochMs()
        val cached = cache
        if (cached != null && now - cacheAtEpochMs < WATCH_PROGRESS_CACHE_TTL_MS) {
            return cached
        }

        val all = prefs.all
        val result = LinkedHashMap<String, WatchProgress>()
        for ((key, v) in all) {
            if (!key.startsWith(WATCH_PROGRESS_KEY_PREFIX)) continue
            val raw = v as? String ?: continue
            val stripped = key.removePrefix(WATCH_PROGRESS_KEY_PREFIX)
            val parsed = runCatching { WatchProgressJson.fromJson(JSONObject(raw)) }.getOrNull() ?: continue
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

    private fun normalizeContinueWatchingEpisodeRemoveId(id: String, episodeId: String): String {
        val trimmedEpisodeId = episodeId.trim()
        if (trimmedEpisodeId.isBlank()) return ""

        val colonParts = trimmedEpisodeId.split(':')
        if (colonParts.size >= 2) {
            val season = colonParts[colonParts.size - 2].toIntOrNull()
            val episode = colonParts[colonParts.size - 1].toIntOrNull()
            if (season != null && episode != null) {
                return "$id:$season:$episode"
            }
        }

        val match = Regex("s(\\d+)e(\\d+)", RegexOption.IGNORE_CASE).find(trimmedEpisodeId)
        if (match != null) {
            val season = match.groupValues.getOrNull(1)?.toIntOrNull()
            val episode = match.groupValues.getOrNull(2)?.toIntOrNull()
            if (season != null && episode != null) {
                return "$id:$season:$episode"
            }
        }

        if (trimmedEpisodeId.startsWith("$id:")) return trimmedEpisodeId
        return "$id:$trimmedEpisodeId"
    }

    private fun getWatchProgressPrefKey(id: String, type: String, episodeId: String?): String {
        val base = "$WATCH_PROGRESS_KEY_PREFIX$type:$id"
        return if (episodeId.isNullOrBlank()) base else "$base:$episodeId"
    }

    private fun getContentDurationPrefKey(id: String, type: String, episodeId: String?): String {
        val base = "$CONTENT_DURATION_KEY_PREFIX$type:$id"
        return if (episodeId.isNullOrBlank()) base else "$base:$episodeId"
    }

    private fun buildWpKeyString(id: String, type: String, episodeId: String? = null): String {
        val base = "$type:$id"
        return if (episodeId.isNullOrBlank()) base else "$base:$episodeId"
    }

    private fun writeTombstones(map: Map<String, Long>) {
        val obj = JSONObject()
        for ((k, v) in map) {
            obj.put(k, v)
        }
        prefs.edit().putString(WP_TOMBSTONES_KEY, obj.toString()).apply()
    }

    private fun writeContinueWatchingRemoved(map: Map<String, Long>) {
        val obj = JSONObject()
        for ((k, v) in map) {
            obj.put(k, v)
        }
        prefs.edit().putString(CONTINUE_WATCHING_REMOVED_KEY, obj.toString()).apply()
    }

    private fun invalidateCache() {
        cache = null
        cacheAtEpochMs = 0L
    }

    private fun debouncedNotify() {
        notificationJob?.cancel()

        val nowElapsedMs = SystemClock.elapsedRealtime()
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
        lastNotificationAtElapsedMs = SystemClock.elapsedRealtime()
        updatesFlow.tryEmit(Unit)
    }

    private object WatchProgressJson {
        fun fromJson(obj: JSONObject): WatchProgress {
            return WatchProgress(
                currentTimeSeconds = obj.optDouble("currentTime", 0.0),
                durationSeconds = obj.optDouble("duration", 0.0),
                lastUpdatedEpochMs = obj.optLong("lastUpdated", 0L),
                remoteImdbId = obj.optString("remoteImdbId").trim().ifBlank { null },
                addonId = obj.optString("addonId").trim().ifBlank { null },
            )
        }
    }

    private fun WatchProgress.toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("currentTime", currentTimeSeconds)
        obj.put("duration", durationSeconds)
        obj.put("lastUpdated", lastUpdatedEpochMs)
        if (!remoteImdbId.isNullOrBlank()) obj.put("remoteImdbId", remoteImdbId)
        if (!addonId.isNullOrBlank()) obj.put("addonId", addonId)

        return obj
    }

    private fun normalizedImdbIdOrNull(raw: String?): String? {
        val value = raw?.trim()?.lowercase(Locale.US).orEmpty()
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
        private const val WATCH_PROGRESS_KEY_PREFIX = "@watch_progress:"
        private const val CONTENT_DURATION_KEY_PREFIX = "@content_duration:"
        private const val WP_TOMBSTONES_KEY = "@wp_tombstones"
        private const val CONTINUE_WATCHING_REMOVED_KEY = "@continue_watching_removed"

        private const val WATCH_PROGRESS_CACHE_TTL_MS = 5_000L
        private const val NOTIFICATION_DEBOUNCE_MS = 1_000L
        private const val MIN_NOTIFICATION_INTERVAL_MS = 500L

        private const val DURATION_UPDATE_THRESHOLD_SECONDS = 60.0
    }
}

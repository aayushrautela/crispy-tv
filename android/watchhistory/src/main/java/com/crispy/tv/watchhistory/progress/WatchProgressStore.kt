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
import kotlin.math.abs
import kotlin.math.max

internal data class WatchProgress(
    val currentTimeSeconds: Double,
    val durationSeconds: Double,
    val lastUpdatedEpochMs: Long,
    val addonId: String? = null,
    val traktSynced: Boolean = false,
    val traktLastSyncedEpochMs: Long? = null,
    val traktProgressPercent: Double? = null,
    val simklSynced: Boolean = false,
    val simklLastSyncedEpochMs: Long? = null,
    val simklProgressPercent: Double? = null,
) {
    fun progressPercentOrZero(): Double {
        val duration = durationSeconds
        if (duration <= 0.0) return 0.0
        return (currentTimeSeconds / duration) * 100.0
    }
}

internal data class UnsyncedProgressItem(
    val key: String,
    val id: String,
    val type: String,
    val episodeId: String?,
    val progress: WatchProgress,
)

internal class WatchProgressStore(
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
        val preserveTimestamp: Boolean = false,
        val forceNotify: Boolean = false,
        val forceWrite: Boolean = false,
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

        if (!options.forceWrite) {
            val existing = getWatchProgress(id = id, type = type, episodeId = episodeId)
            if (existing != null) {
                val timeDiff = abs(existing.currentTimeSeconds - progress.currentTimeSeconds)
                val durationDiff = abs(existing.durationSeconds - progress.durationSeconds)
                if (timeDiff < SIGNIFICANT_TIME_CHANGE_SECONDS && durationDiff < SIGNIFICANT_DURATION_CHANGE_SECONDS) {
                    return
                }
            }
        }

        val timestamp =
            if (options.preserveTimestamp && progress.lastUpdatedEpochMs > 0) {
                progress.lastUpdatedEpochMs
            } else {
                nowEpochMs()
            }

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
        for ((k, v) in all) {
            val key = k as? String ?: continue
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

    fun getUnsyncedProgress(): List<UnsyncedProgressItem> {
        return runCatching {
            val all = getAllWatchProgress()
            val tombstones = getWatchProgressTombstones()
            val result = ArrayList<UnsyncedProgressItem>()

            for ((key, progress) in all) {
                val parts = key.split(':')
                if (parts.size < 2) continue

                val baseKey = "${parts[0]}:${parts[1]}"
                val tombAt = max(tombstones[key] ?: Long.MIN_VALUE, tombstones[baseKey] ?: Long.MIN_VALUE)
                    .takeIf { it != Long.MIN_VALUE }
                if (tombAt != null && progress.lastUpdatedEpochMs <= tombAt) {
                    continue
                }

                val needsTrakt = !progress.traktSynced ||
                    (progress.traktLastSyncedEpochMs != null && progress.lastUpdatedEpochMs > progress.traktLastSyncedEpochMs)
                val needsSimkl = !progress.simklSynced ||
                    (progress.simklLastSyncedEpochMs != null && progress.lastUpdatedEpochMs > progress.simklLastSyncedEpochMs)
                if (!needsTrakt && !needsSimkl) continue

                val episodeId = if (parts.size > 2) parts.subList(2, parts.size).joinToString(":") else null
                result +=
                    UnsyncedProgressItem(
                        key = key,
                        id = parts[1],
                        type = parts[0],
                        episodeId = episodeId,
                        progress = progress,
                    )
            }

            result
        }.getOrElse {
            Log.w(logTag, "Failed to compute unsynced progress", it)
            emptyList()
        }
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

    fun updateTraktSyncStatus(
        id: String,
        type: String,
        traktSynced: Boolean,
        traktProgressPercent: Double? = null,
        episodeId: String? = null,
        exactTimeSeconds: Double? = null,
    ) {
        val existing = getWatchProgress(id = id, type = type, episodeId = episodeId) ?: return
        val highestProgress = highest(existing.traktProgressPercent, traktProgressPercent)
        val highestCurrentTime = if (exactTimeSeconds != null) max(exactTimeSeconds, existing.currentTimeSeconds) else existing.currentTimeSeconds

        val updated =
            existing.copy(
                currentTimeSeconds = highestCurrentTime,
                traktSynced = traktSynced,
                traktLastSyncedEpochMs = if (traktSynced) nowEpochMs() else existing.traktLastSyncedEpochMs,
                traktProgressPercent = highestProgress,
            )
        setWatchProgress(id = id, type = type, progress = updated, episodeId = episodeId, options = SetOptions(preserveTimestamp = true, forceWrite = true))
    }

    fun mergeWithTraktProgress(
        id: String,
        type: String,
        traktProgressPercent: Double,
        traktPausedAtEpochMs: Long,
        episodeId: String? = null,
        exactTimeSeconds: Double? = null,
    ) {
        mergeWithProviderProgress(
            id = id,
            type = type,
            provider = Provider.TRAKT,
            providerProgressPercent = traktProgressPercent,
            pausedAtEpochMs = traktPausedAtEpochMs,
            episodeId = episodeId,
            exactTimeSeconds = exactTimeSeconds,
        )
    }

    fun updateSimklSyncStatus(
        id: String,
        type: String,
        simklSynced: Boolean,
        simklProgressPercent: Double? = null,
        episodeId: String? = null,
        exactTimeSeconds: Double? = null,
    ) {
        val existing = getWatchProgress(id = id, type = type, episodeId = episodeId) ?: return
        val highestProgress = highest(existing.simklProgressPercent, simklProgressPercent)
        val highestCurrentTime = if (exactTimeSeconds != null) max(exactTimeSeconds, existing.currentTimeSeconds) else existing.currentTimeSeconds

        val updated =
            existing.copy(
                currentTimeSeconds = highestCurrentTime,
                simklSynced = simklSynced,
                simklLastSyncedEpochMs = if (simklSynced) nowEpochMs() else existing.simklLastSyncedEpochMs,
                simklProgressPercent = highestProgress,
            )
        setWatchProgress(id = id, type = type, progress = updated, episodeId = episodeId, options = SetOptions(preserveTimestamp = true, forceWrite = true))
    }

    fun mergeWithSimklProgress(
        id: String,
        type: String,
        simklProgressPercent: Double,
        simklPausedAtEpochMs: Long,
        episodeId: String? = null,
        exactTimeSeconds: Double? = null,
    ) {
        mergeWithProviderProgress(
            id = id,
            type = type,
            provider = Provider.SIMKL,
            providerProgressPercent = simklProgressPercent,
            pausedAtEpochMs = simklPausedAtEpochMs,
            episodeId = episodeId,
            exactTimeSeconds = exactTimeSeconds,
        )
    }

    private enum class Provider { TRAKT, SIMKL }

    private fun mergeWithProviderProgress(
        id: String,
        type: String,
        provider: Provider,
        providerProgressPercent: Double,
        pausedAtEpochMs: Long,
        episodeId: String?,
        exactTimeSeconds: Double?,
    ) {
        val providerProgress = providerProgressPercent.coerceIn(0.0, 100.0)
        val timestamp = pausedAtEpochMs.takeIf { it > 0 } ?: nowEpochMs()

        val local = getWatchProgress(id = id, type = type, episodeId = episodeId)
        if (local == null) {
            var duration = getContentDurationSeconds(id = id, type = type, episodeId = episodeId)
            var currentTime: Double

            if (exactTimeSeconds != null && exactTimeSeconds > 0.0) {
                currentTime = exactTimeSeconds
                if (duration == null || duration <= 0.0) {
                    duration = if (providerProgress > 0.0) (exactTimeSeconds / providerProgress) * 100.0 else 0.0
                }
            } else {
                if (duration == null || duration <= 0.0) {
                    duration = estimateDurationSeconds(type = type, episodeId = episodeId)
                }
                currentTime = (providerProgress / 100.0) * duration
            }

            val base =
                WatchProgress(
                    currentTimeSeconds = currentTime,
                    durationSeconds = duration ?: 0.0,
                    lastUpdatedEpochMs = timestamp,
                    traktSynced = provider == Provider.TRAKT,
                    simklSynced = provider == Provider.SIMKL,
                    traktLastSyncedEpochMs = if (provider == Provider.TRAKT) nowEpochMs() else null,
                    simklLastSyncedEpochMs = if (provider == Provider.SIMKL) nowEpochMs() else null,
                    traktProgressPercent = if (provider == Provider.TRAKT) providerProgress else null,
                    simklProgressPercent = if (provider == Provider.SIMKL) providerProgress else null,
                )

            setWatchProgress(
                id = id,
                type = type,
                progress = base,
                episodeId = episodeId,
                options = SetOptions(preserveTimestamp = true, forceWrite = true),
            )
            return
        }

        val localPercent = local.progressPercentOrZero()
        val diff = abs(providerProgress - localPercent)
        if (diff < MIN_PROGRESS_DIFF_PERCENT && providerProgress < 100.0 && localPercent < 100.0) {
            // Still mark provider as synced (and store provider progress) to avoid immediate re-upload loops.
            when (provider) {
                Provider.TRAKT -> updateTraktSyncStatus(id, type, traktSynced = true, traktProgressPercent = providerProgress, episodeId = episodeId)
                Provider.SIMKL -> updateSimklSyncStatus(id, type, simklSynced = true, simklProgressPercent = providerProgress, episodeId = episodeId)
            }
            return
        }

        var duration = local.durationSeconds
        var currentTime: Double

        if (exactTimeSeconds != null && exactTimeSeconds > 0.0 && local.durationSeconds > 0.0) {
            currentTime = exactTimeSeconds

            val calculatedDuration = if (providerProgress > 0.0) (exactTimeSeconds / providerProgress) * 100.0 else local.durationSeconds
            if (abs(calculatedDuration - local.durationSeconds) > DURATION_RECALC_THRESHOLD_SECONDS) {
                duration = calculatedDuration
            }
        } else if (local.durationSeconds > 0.0) {
            currentTime = (providerProgress / 100.0) * local.durationSeconds
        } else {
            val storedDuration = getContentDurationSeconds(id = id, type = type, episodeId = episodeId)
            duration = storedDuration ?: 0.0

            if (duration <= 0.0) {
                if (exactTimeSeconds != null && exactTimeSeconds > 0.0) {
                    duration = if (providerProgress > 0.0) (exactTimeSeconds / providerProgress) * 100.0 else 0.0
                    currentTime = exactTimeSeconds
                } else {
                    duration = estimateDurationSeconds(type = type, episodeId = episodeId)
                    currentTime = (providerProgress / 100.0) * duration
                }
            } else {
                currentTime = if (exactTimeSeconds != null && exactTimeSeconds > 0.0) exactTimeSeconds else (providerProgress / 100.0) * duration
            }
        }

        val updated =
            local.copy(
                currentTimeSeconds = currentTime,
                durationSeconds = duration,
                lastUpdatedEpochMs = timestamp,
                traktSynced = if (provider == Provider.TRAKT) true else local.traktSynced,
                simklSynced = if (provider == Provider.SIMKL) true else local.simklSynced,
                traktLastSyncedEpochMs = if (provider == Provider.TRAKT) nowEpochMs() else local.traktLastSyncedEpochMs,
                simklLastSyncedEpochMs = if (provider == Provider.SIMKL) nowEpochMs() else local.simklLastSyncedEpochMs,
                traktProgressPercent = if (provider == Provider.TRAKT) providerProgress else local.traktProgressPercent,
                simklProgressPercent = if (provider == Provider.SIMKL) providerProgress else local.simklProgressPercent,
            )

        setWatchProgress(
            id = id,
            type = type,
            progress = updated,
            episodeId = episodeId,
            options = SetOptions(preserveTimestamp = true, forceWrite = true),
        )
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

    private fun highest(a: Double?, b: Double?): Double? {
        if (a == null) return b
        if (b == null) return a
        return max(a, b)
    }

    private object WatchProgressJson {
        fun fromJson(obj: JSONObject): WatchProgress {
            return WatchProgress(
                currentTimeSeconds = obj.optDouble("currentTime", 0.0),
                durationSeconds = obj.optDouble("duration", 0.0),
                lastUpdatedEpochMs = obj.optLong("lastUpdated", 0L),
                addonId = obj.optString("addonId").trim().ifBlank { null },
                traktSynced = obj.optBoolean("traktSynced", false),
                traktLastSyncedEpochMs = obj.optLongOrNull("traktLastSynced"),
                traktProgressPercent = obj.optDoubleOrNull("traktProgress"),
                simklSynced = obj.optBoolean("simklSynced", false),
                simklLastSyncedEpochMs = obj.optLongOrNull("simklLastSynced"),
                simklProgressPercent = obj.optDoubleOrNull("simklProgress"),
            )
        }

        private fun JSONObject.optLongOrNull(key: String): Long? {
            if (!has(key)) return null
            val value = optLong(key, Long.MIN_VALUE)
            return value.takeIf { it != Long.MIN_VALUE }
        }

        private fun JSONObject.optDoubleOrNull(key: String): Double? {
            if (!has(key)) return null
            val value = optDouble(key, Double.NaN)
            return value.takeIf { !it.isNaN() }
        }
    }

    private fun WatchProgress.toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("currentTime", currentTimeSeconds)
        obj.put("duration", durationSeconds)
        obj.put("lastUpdated", lastUpdatedEpochMs)
        if (!addonId.isNullOrBlank()) obj.put("addonId", addonId)

        obj.put("traktSynced", traktSynced)
        if (traktLastSyncedEpochMs != null) obj.put("traktLastSynced", traktLastSyncedEpochMs)
        if (traktProgressPercent != null) obj.put("traktProgress", traktProgressPercent)

        obj.put("simklSynced", simklSynced)
        if (simklLastSyncedEpochMs != null) obj.put("simklLastSynced", simklLastSyncedEpochMs)
        if (simklProgressPercent != null) obj.put("simklProgress", simklProgressPercent)

        return obj
    }

    private companion object {
        private const val WATCH_PROGRESS_KEY_PREFIX = "@watch_progress:"
        private const val CONTENT_DURATION_KEY_PREFIX = "@content_duration:"
        private const val WP_TOMBSTONES_KEY = "@wp_tombstones"
        private const val CONTINUE_WATCHING_REMOVED_KEY = "@continue_watching_removed"

        private const val WATCH_PROGRESS_CACHE_TTL_MS = 5_000L
        private const val NOTIFICATION_DEBOUNCE_MS = 1_000L
        private const val MIN_NOTIFICATION_INTERVAL_MS = 500L

        private const val SIGNIFICANT_TIME_CHANGE_SECONDS = 5.0
        private const val SIGNIFICANT_DURATION_CHANGE_SECONDS = 1.0
        private const val DURATION_UPDATE_THRESHOLD_SECONDS = 60.0
        private const val DURATION_RECALC_THRESHOLD_SECONDS = 300.0
        private const val MIN_PROGRESS_DIFF_PERCENT = 5.0
    }
}

private fun estimateDurationSeconds(type: String, episodeId: String?): Double {
    return when {
        type == "movie" -> 6600.0
        !episodeId.isNullOrBlank() -> 2700.0
        else -> 3600.0
    }
}

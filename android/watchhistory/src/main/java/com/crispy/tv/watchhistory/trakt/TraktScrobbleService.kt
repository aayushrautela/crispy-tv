package com.crispy.tv.watchhistory.trakt

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.Locale
import kotlin.math.round

internal class TraktScrobbleService(
    private val traktApi: TraktApi,
    val completionThresholdPercent: Double = DEFAULT_COMPLETION_THRESHOLD_PERCENT,
    private val logTag: String = "TraktScrobbleService",
) {
    internal sealed interface TraktContentData {
        val title: String

        data class Movie(
            override val title: String,
            val year: Int? = null,
            val imdbId: String,
        ) : TraktContentData

        data class Episode(
            override val title: String,
            val showTitle: String,
            val showYear: Int? = null,
            val showImdbId: String,
            val season: Int,
            val episode: Int,
        ) : TraktContentData
    }

    private val apiMutex = Mutex()
    private var lastApiCallAtElapsedMs: Long = 0L

    private val stateMutex = Mutex()
    private val currentlyWatching = LinkedHashSet<String>()
    private val lastSyncTimesElapsedMs = LinkedHashMap<String, Long>()
    private val lastStopCallsElapsedMs = LinkedHashMap<String, Long>()
    private val scrobbledAtElapsedMs = LinkedHashMap<String, Long>()

    suspend fun scrobbleStart(contentData: TraktContentData, progressPercent: Double): Boolean {
        val watchingKey = watchingKey(contentData)
        val nowElapsedMs = SystemClock.elapsedRealtime()

        stateMutex.withLock {
            cleanupOldScrobbles(nowElapsedMs)
            if (isRecentlyScrobbledLocked(watchingKey, nowElapsedMs)) {
                return true
            }
            val lastStop = lastStopCallsElapsedMs[watchingKey]
            if (lastStop != null && nowElapsedMs - lastStop < RECENT_STOP_BLOCK_MS) {
                return true
            }
            if (currentlyWatching.contains(watchingKey)) {
                return true
            }
        }

        val payload = buildScrobblePayload(contentData, progressPercent)
        val ok = postScrobble(path = "/scrobble/start", payload = payload)
        if (ok) {
            stateMutex.withLock {
                currentlyWatching.add(watchingKey)
            }
        }
        return ok
    }

    suspend fun scrobblePause(contentData: TraktContentData, progressPercent: Double, force: Boolean = false): Boolean {
        val watchingKey = watchingKey(contentData)
        val nowElapsedMs = SystemClock.elapsedRealtime()

        stateMutex.withLock {
            val last = lastSyncTimesElapsedMs[watchingKey] ?: 0L
            if (!force && nowElapsedMs - last < MIN_PAUSE_DEBOUNCE_MS) {
                return true
            }
            lastSyncTimesElapsedMs[watchingKey] = nowElapsedMs
        }

        val payload = buildScrobblePayload(contentData, progressPercent)
        return postScrobble(path = "/scrobble/stop", payload = payload)
    }

    suspend fun scrobbleStop(contentData: TraktContentData, progressPercent: Double): Boolean {
        val watchingKey = watchingKey(contentData)
        val nowElapsedMs = SystemClock.elapsedRealtime()

        stateMutex.withLock {
            val lastStop = lastStopCallsElapsedMs[watchingKey]
            if (lastStop != null && nowElapsedMs - lastStop < STOP_DEBOUNCE_MS) {
                return true
            }
            lastStopCallsElapsedMs[watchingKey] = nowElapsedMs
        }

        val payload = buildScrobblePayload(contentData, progressPercent)
        val ok = postScrobble(path = "/scrobble/stop", payload = payload)

        if (ok) {
            stateMutex.withLock {
                currentlyWatching.remove(watchingKey)
                if (progressPercent >= completionThresholdPercent) {
                    scrobbledAtElapsedMs[watchingKey] = nowElapsedMs
                }
            }
            return true
        }

        // If failed, allow retry.
        stateMutex.withLock {
            lastStopCallsElapsedMs.remove(watchingKey)
        }
        return false
    }

    private suspend fun postScrobble(path: String, payload: JSONObject): Boolean {
        val response =
            apiMutex.withLock {
                waitForRateLimitLocked()
                traktApi.postRaw(path = path, payload = payload)
            } ?: return false

        if (response.code == 429) {
            Log.w(logTag, "Trakt rate limited (429). Treating as success.")
            return true
        }

        if (response.code !in 200..299) {
            Log.w(logTag, "Trakt scrobble non-2xx (${response.code}) at $path")
            return false
        }

        return true
    }

    private fun watchingKey(contentData: TraktContentData): String {
        return when (contentData) {
            is TraktContentData.Movie -> "movie:${normalizedImdb(contentData.imdbId)}"
            is TraktContentData.Episode -> {
                val imdb = normalizedImdb(contentData.showImdbId)
                "episode:$imdb:S${contentData.season}E${contentData.episode}"
            }
        }
    }

    private fun buildScrobblePayload(contentData: TraktContentData, progressPercent: Double): JSONObject {
        val progress = progressPercent.coerceIn(0.0, 100.0)
        return when (contentData) {
            is TraktContentData.Movie -> {
                val movie = JSONObject().put("title", contentData.title)
                val year = contentData.year
                if (year != null && year in 1800..3000) {
                    movie.put("year", year)
                }
                movie.put("ids", JSONObject().put("imdb", normalizedImdb(contentData.imdbId)))

                JSONObject()
                    .put("movie", movie)
                    .put("progress", round2(progress))
            }

            is TraktContentData.Episode -> {
                val show = JSONObject().put("title", contentData.showTitle)
                val year = contentData.showYear
                if (year != null && year in 1800..3000) {
                    show.put("year", year)
                }
                show.put("ids", JSONObject().put("imdb", normalizedImdb(contentData.showImdbId)))

                val episode =
                    JSONObject()
                        .put("season", contentData.season)
                        .put("number", contentData.episode)

                JSONObject()
                    .put("show", show)
                    .put("episode", episode)
                    .put("progress", round2(progress))
            }
        }
    }

    private fun round2(value: Double): Double {
        return round(value * 100.0) / 100.0
    }

    private fun normalizedImdb(raw: String): String {
        val value = raw.trim().lowercase(Locale.US)
        return if (value.startsWith("tt")) value else "tt$value"
    }

    private suspend fun waitForRateLimitLocked() {
        val now = SystemClock.elapsedRealtime()
        val delta = now - lastApiCallAtElapsedMs
        if (delta < MIN_API_INTERVAL_MS) {
            delay(MIN_API_INTERVAL_MS - delta)
        }
        lastApiCallAtElapsedMs = SystemClock.elapsedRealtime()
    }

    private fun cleanupOldScrobbles(nowElapsedMs: Long) {
        val it = scrobbledAtElapsedMs.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (nowElapsedMs - entry.value > SCROBBLE_EXPIRY_MS) {
                it.remove()
            }
        }
    }

    private fun isRecentlyScrobbledLocked(watchingKey: String, nowElapsedMs: Long): Boolean {
        val ts = scrobbledAtElapsedMs[watchingKey] ?: return false
        return nowElapsedMs - ts < SCROBBLE_EXPIRY_MS
    }

    private companion object {
        private const val DEFAULT_COMPLETION_THRESHOLD_PERCENT = 80.0

        private const val MIN_API_INTERVAL_MS = 500L
        private const val SCROBBLE_EXPIRY_MS = 46L * 60L * 1000L
        private const val RECENT_STOP_BLOCK_MS = 30L * 1000L
        private const val STOP_DEBOUNCE_MS = 1000L
        private const val MIN_PAUSE_DEBOUNCE_MS = 100L
    }
}

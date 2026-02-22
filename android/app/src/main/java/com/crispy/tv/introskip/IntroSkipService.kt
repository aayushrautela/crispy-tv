package com.crispy.tv.introskip

import android.util.Log
import com.crispy.tv.network.CrispyHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

private const val TAG = "IntroSkipService"
private const val DEFAULT_INTRO_DB_BASE_URL = "https://api.introdb.app"
private const val ANI_SKIP_BASE_URL = "https://api.aniskip.com/v2"
private const val KITSU_API_BASE_URL = "https://kitsu.io/api/edge"
private const val ARM_IMDB_BASE_URL = "https://arm.haglund.dev/api/v2/imdb"
private const val INTRODB_TIMEOUT_MS = 5_000
private const val ANISKIP_TIMEOUT_MS = 5_000
private const val MAL_LOOKUP_TIMEOUT_MS = 5_000
private const val MIN_INTERVAL_MS = 500L

data class IntroSkipRequest(
    val imdbId: String? = null,
    val season: Int,
    val episode: Int,
    val malId: Int? = null,
    val kitsuId: Int? = null
)

enum class IntroSkipSegmentType {
    INTRO,
    OUTRO,
    RECAP,
    OP,
    ED,
    MIXED_OP,
    MIXED_ED,
    UNKNOWN;

    companion object {
        fun fromWire(value: String?): IntroSkipSegmentType {
            return when (value?.lowercase()) {
                "intro" -> INTRO
                "outro" -> OUTRO
                "recap" -> RECAP
                "op" -> OP
                "ed" -> ED
                "mixed-op" -> MIXED_OP
                "mixed-ed" -> MIXED_ED
                else -> UNKNOWN
            }
        }
    }
}

enum class IntroSkipProvider {
    INTRO_DB,
    ANI_SKIP
}

data class IntroSkipInterval(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val segmentType: IntroSkipSegmentType,
    val provider: IntroSkipProvider,
    val skipId: String? = null
) {
    val stableKey: String = "${provider.name}:${segmentType.name}:$startTimeMs:$endTimeMs"

    fun isActiveAt(positionMs: Long): Boolean {
        return positionMs >= startTimeMs && positionMs < endTimeMs - 500L
    }
}

interface IntroSkipService {
    suspend fun getSkipIntervals(request: IntroSkipRequest): List<IntroSkipInterval>
}

class RemoteIntroSkipService(
    private val httpClient: CrispyHttpClient,
    introDbBaseUrl: String = DEFAULT_INTRO_DB_BASE_URL,
    private val cacheTtlMs: Long = TimeUnit.MINUTES.toMillis(10)
) : IntroSkipService {
    private val introDbBaseUrl = introDbBaseUrl.trim().ifBlank { DEFAULT_INTRO_DB_BASE_URL }.trimEnd('/')
    private val cacheMutex = Mutex()
    private val cache = mutableMapOf<String, CacheEntry>()

    override suspend fun getSkipIntervals(request: IntroSkipRequest): List<IntroSkipInterval> {
        val normalized = request.normalize() ?: return emptyList()
        val nowMs = System.currentTimeMillis()

        val cached = cacheMutex.withLock {
            val entry = cache[normalized.cacheKey] ?: return@withLock null
            if (nowMs - entry.timestampMs <= cacheTtlMs) {
                entry.intervals
            } else {
                cache.remove(normalized.cacheKey)
                null
            }
        }
        if (cached != null) {
            return cached
        }

        val fetched =
            withContext(Dispatchers.IO) {
                runCatching {
                    fetchIntervals(normalized)
                }.onFailure { error ->
                    Log.w(TAG, "Failed to fetch intro skip intervals", error)
                }.getOrDefault(emptyList())
            }
                .sortedBy { it.startTimeMs }

        cacheMutex.withLock {
            cache[normalized.cacheKey] = CacheEntry(timestampMs = nowMs, intervals = fetched)
            trimExpiredCache(nowMs)
        }

        return fetched
    }

    private suspend fun fetchIntervals(request: NormalizedIntroSkipRequest): List<IntroSkipInterval> {
        request.imdbId?.let { imdbId ->
            val introDbIntervals = fetchFromIntroDb(imdbId, request.season, request.episode)
            if (introDbIntervals.isNotEmpty()) {
                return introDbIntervals
            }
        }

        val malId =
            when {
                request.malId != null -> request.malId
                request.kitsuId != null -> resolveMalIdFromKitsu(request.kitsuId)
                request.imdbId != null -> resolveMalIdFromArm(request.imdbId)
                else -> null
            }

        if (malId == null) {
            return emptyList()
        }

        return fetchFromAniSkip(malId, request.episode)
    }

    private suspend fun fetchFromIntroDb(
        imdbId: String,
        season: Int,
        episode: Int
    ): List<IntroSkipInterval> {
        val url =
            buildUrl(
                base = "$introDbBaseUrl/segments",
                query =
                    mapOf(
                        "imdb_id" to imdbId,
                        "season" to season.toString(),
                        "episode" to episode.toString()
                    )
            )

        val response = httpGet(url, INTRODB_TIMEOUT_MS) ?: return emptyList()
        if (response.statusCode == 404) {
            return emptyList()
        }
        if (response.statusCode !in 200..299) {
            Log.w(TAG, "IntroDB returned HTTP ${response.statusCode}")
            return emptyList()
        }

        val payload = parseJsonObject(response.body) ?: return emptyList()
        val intervals = mutableListOf<IntroSkipInterval>()

        addIntroDbInterval(intervals, payload.optJSONObject("intro"), IntroSkipSegmentType.INTRO)
        addIntroDbInterval(intervals, payload.optJSONObject("recap"), IntroSkipSegmentType.RECAP)
        addIntroDbInterval(intervals, payload.optJSONObject("outro"), IntroSkipSegmentType.OUTRO)

        return intervals
    }

    private fun addIntroDbInterval(
        target: MutableList<IntroSkipInterval>,
        segment: JSONObject?,
        segmentType: IntroSkipSegmentType
    ) {
        if (segment == null) {
            return
        }

        val startSec = segment.optDouble("start_sec", Double.NaN)
        val endSec = segment.optDouble("end_sec", Double.NaN)
        val interval =
            buildInterval(
                startMs = secondsToMillis(startSec),
                endMs = secondsToMillis(endSec),
                segmentType = segmentType,
                provider = IntroSkipProvider.INTRO_DB,
                skipId = null
            )
        if (interval != null) {
            target += interval
        }
    }

    private suspend fun fetchFromAniSkip(malId: Int, episode: Int): List<IntroSkipInterval> {
        val query = "types=op&types=ed&types=recap&types=mixed-op&types=mixed-ed&episodeLength=0"
        val url = "$ANI_SKIP_BASE_URL/skip-times/$malId/$episode?$query"
        val response = httpGet(url, ANISKIP_TIMEOUT_MS) ?: return emptyList()
        if (response.statusCode == 404) {
            return emptyList()
        }
        if (response.statusCode !in 200..299) {
            Log.w(TAG, "AniSkip returned HTTP ${response.statusCode}")
            return emptyList()
        }

        val payload = parseJsonObject(response.body) ?: return emptyList()
        if (!payload.optBoolean("found", false)) {
            return emptyList()
        }

        val results = payload.optJSONArray("results") ?: return emptyList()
        val intervals = mutableListOf<IntroSkipInterval>()

        for (index in 0 until results.length()) {
            val item = results.optJSONObject(index) ?: continue
            val intervalJson = item.optJSONObject("interval") ?: continue
            val startSec = intervalJson.optDouble("startTime", Double.NaN)
            val endSec = intervalJson.optDouble("endTime", Double.NaN)
            val interval =
                buildInterval(
                    startMs = secondsToMillis(startSec),
                    endMs = secondsToMillis(endSec),
                    segmentType = IntroSkipSegmentType.fromWire(item.optString("skipType")),
                    provider = IntroSkipProvider.ANI_SKIP,
                    skipId = item.optString("skipId").ifBlank { null }
                )
            if (interval != null) {
                intervals += interval
            }
        }

        return intervals
    }

    private suspend fun resolveMalIdFromKitsu(kitsuId: Int): Int? {
        val url = "$KITSU_API_BASE_URL/anime/$kitsuId/mappings"
        val response = httpGet(url, MAL_LOOKUP_TIMEOUT_MS) ?: return null
        if (response.statusCode !in 200..299) {
            return null
        }

        val payload = parseJsonObject(response.body) ?: return null
        val mappings = payload.optJSONArray("data") ?: return null

        for (index in 0 until mappings.length()) {
            val item = mappings.optJSONObject(index) ?: continue
            val attributes = item.optJSONObject("attributes") ?: continue
            val externalSite = attributes.optString("externalSite")
            if (externalSite != "myanimelist/anime") {
                continue
            }

            val externalId = attributes.optString("externalId").toIntOrNull()
            if (externalId != null && externalId > 0) {
                return externalId
            }
        }

        return null
    }

    private suspend fun resolveMalIdFromArm(imdbId: String): Int? {
        val url = "$ARM_IMDB_BASE_URL/$imdbId?include=myanimelist"
        val response = httpGet(url, MAL_LOOKUP_TIMEOUT_MS) ?: return null
        if (response.statusCode !in 200..299) {
            return null
        }

        val payload = parseJsonObject(response.body) ?: return null
        val results = payload.optJSONArray("results") ?: return null

        for (index in 0 until results.length()) {
            val item = results.optJSONObject(index) ?: continue
            val malId = item.optInt("myanimelist", -1)
            if (malId > 0) {
                return malId
            }
        }

        return null
    }

    private fun trimExpiredCache(nowMs: Long) {
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (nowMs - entry.timestampMs > cacheTtlMs) {
                iterator.remove()
            }
        }
    }

    private suspend fun httpGet(url: String, timeoutMs: Int): HttpResponse? {
        return runCatching {
            val response =
                httpClient.get(
                    url = url.toHttpUrl(),
                    headers = Headers.headersOf("Accept", "application/json"),
                    callTimeoutMs = timeoutMs.toLong(),
                )
            HttpResponse(statusCode = response.code, body = response.body)
        }.onFailure { error ->
            Log.w(TAG, "HTTP request failed for $url", error)
        }.getOrNull()
    }

    private fun parseJsonObject(rawBody: String?): JSONObject? {
        if (rawBody.isNullOrBlank()) {
            return null
        }

        return runCatching {
            JSONObject(rawBody)
        }.getOrNull()
    }

    private fun secondsToMillis(seconds: Double): Long {
        if (seconds.isNaN() || !seconds.isFinite()) {
            return -1L
        }
        return (seconds * 1000.0).toLong()
    }

    private fun buildInterval(
        startMs: Long,
        endMs: Long,
        segmentType: IntroSkipSegmentType,
        provider: IntroSkipProvider,
        skipId: String?
    ): IntroSkipInterval? {
        if (startMs < 0L || endMs < 0L || endMs - startMs < MIN_INTERVAL_MS) {
            return null
        }

        return IntroSkipInterval(
            startTimeMs = startMs,
            endTimeMs = endMs,
            segmentType = segmentType,
            provider = provider,
            skipId = skipId
        )
    }
}

private data class CacheEntry(
    val timestampMs: Long,
    val intervals: List<IntroSkipInterval>
)

private data class HttpResponse(
    val statusCode: Int,
    val body: String?
)

private data class NormalizedIntroSkipRequest(
    val imdbId: String?,
    val season: Int,
    val episode: Int,
    val malId: Int?,
    val kitsuId: Int?,
    val cacheKey: String
)

private fun IntroSkipRequest.normalize(): NormalizedIntroSkipRequest? {
    val normalizedSeason = season.takeIf { it > 0 } ?: return null
    val normalizedEpisode = episode.takeIf { it > 0 } ?: return null
    val normalizedImdbId = imdbId?.trim()?.takeIf { it.matches(Regex("tt\\d+")) }
    val normalizedMalId = malId?.takeIf { it > 0 }
    val normalizedKitsuId = kitsuId?.takeIf { it > 0 }

    if (normalizedImdbId == null && normalizedMalId == null && normalizedKitsuId == null) {
        return null
    }

    val cacheKey =
        listOf(
            normalizedImdbId.orEmpty(),
            normalizedSeason.toString(),
            normalizedEpisode.toString(),
            normalizedMalId?.toString().orEmpty(),
            normalizedKitsuId?.toString().orEmpty()
        ).joinToString(separator = "|")

    return NormalizedIntroSkipRequest(
        imdbId = normalizedImdbId,
        season = normalizedSeason,
        episode = normalizedEpisode,
        malId = normalizedMalId,
        kitsuId = normalizedKitsuId,
        cacheKey = cacheKey
    )
}

private fun buildUrl(base: String, query: Map<String, String>): String {
    if (query.isEmpty()) {
        return base
    }

    val encodedQuery =
        query.entries.joinToString(separator = "&") { entry ->
            val encodedValue =
                URLEncoder.encode(
                    entry.value,
                    StandardCharsets.UTF_8.toString()
                )
            "${entry.key}=$encodedValue"
        }
    return "$base?$encodedQuery"
}

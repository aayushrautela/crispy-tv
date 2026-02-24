package com.crispy.tv.metadata

import android.content.Context
import android.util.Log
import com.crispy.tv.domain.watch.AddonEpisodeInfo
import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.player.EpisodeListProvider
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/**
 * Fetches episode lists from Cinemeta / Stremio addon `/meta/{type}/{id}.json`
 * and converts `meta.videos[]` to [AddonEpisodeInfo].
 *
 * Results are cached in-memory for [CACHE_TTL_MS] (5 minutes) to match Nuvio's
 * `getCachedMetadata` behavior.
 */
internal class AddonEpisodeListProvider(
    context: Context,
    addonManifestUrlsCsv: String,
    private val httpClient: CrispyHttpClient,
) : EpisodeListProvider {

    private val registry = MetadataAddonRegistry(context.applicationContext, addonManifestUrlsCsv)

    private data class CacheEntry(val episodes: List<AddonEpisodeInfo>, val fetchedAtMs: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    override suspend fun fetchEpisodeList(
        mediaType: String,
        contentId: String,
    ): List<AddonEpisodeInfo>? {
        val cacheKey = "$mediaType:$contentId"
        val now = System.currentTimeMillis()

        cache[cacheKey]?.let { entry ->
            if (now - entry.fetchedAtMs < CACHE_TTL_MS) return entry.episodes
        }

        val seeds = registry.orderedSeeds()
        if (seeds.isEmpty()) {
            Log.w(TAG, "No addon seeds available for episode list fetch")
            return null
        }

        for (seed in seeds) {
            try {
                val encodedId = URLEncoder.encode(contentId, "UTF-8")
                val url = buildString {
                    append(seed.baseUrl.trimEnd('/'))
                    append("/meta/")
                    append(mediaType)
                    append('/')
                    append(encodedId)
                    append(".json")
                    seed.encodedQuery?.let { q ->
                        if (q.isNotBlank()) {
                            append('?')
                            append(q)
                        }
                    }
                }

                val response = httpClient.get(
                    url.toHttpUrl(),
                    headers = mapOf("Accept" to "application/json"),
                )
                val body = response.body?.string()
                if (body.isNullOrBlank()) continue

                val root = JSONObject(body)
                val meta = root.optJSONObject("meta") ?: continue
                val videosArr = meta.optJSONArray("videos")
                if (videosArr == null || videosArr.length() == 0) continue

                val episodes = buildList {
                    for (i in 0 until videosArr.length()) {
                        val v = videosArr.optJSONObject(i) ?: continue
                        val season = v.optInt("season", -1)
                        val episode = v.optInt("episode", -1)
                        if (season < 0 || episode < 0) continue
                        add(
                            AddonEpisodeInfo(
                                season = season,
                                episode = episode,
                                title = v.optString("title").trim().ifEmpty { null },
                                released = v.optString("released").trim().ifEmpty { null },
                            )
                        )
                    }
                }

                if (episodes.isNotEmpty()) {
                    cache[cacheKey] = CacheEntry(episodes, now)
                    return episodes
                }
            } catch (e: Exception) {
                Log.w(TAG, "Addon episode list fetch failed for seed ${seed.baseUrl}: ${e.message}")
            }
        }

        return null
    }

    companion object {
        private const val TAG = "AddonEpisodeList"
        private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
    }
}

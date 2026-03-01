package com.crispy.tv.metadata

import com.crispy.tv.domain.metadata.normalizeNuvioMediaId
import com.crispy.tv.domain.watch.EpisodeInfo
import com.crispy.tv.metadata.tmdb.TmdbEnrichmentRepository
import com.crispy.tv.metadata.tmdb.TmdbTvDetails
import com.crispy.tv.player.EpisodeListProvider
import com.crispy.tv.player.MetadataLabMediaType
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal class TmdbEpisodeListProvider(
    private val tmdbEnrichmentRepository: TmdbEnrichmentRepository,
    private val localeProvider: () -> Locale = { Locale.getDefault() },
) : EpisodeListProvider {
    private data class SeasonCacheEntry(
        val episodes: List<EpisodeInfo>,
        val fetchedAtEpochMs: Long,
    )

    private data class ShowCacheEntry(
        val tmdbId: Int,
        val numberOfSeasons: Int,
        val fetchedAtEpochMs: Long,
    )

    private val showCache = mutableMapOf<String, ShowCacheEntry>()
    private val seasonCache = mutableMapOf<String, SeasonCacheEntry>()

    private val transportSemaphore = Semaphore(3)

    override suspend fun fetchEpisodeList(
        mediaType: String,
        contentId: String,
        seasonHint: Int?,
    ): List<EpisodeInfo>? = withContext(Dispatchers.IO) {
        if (!mediaType.trim().equals("series", ignoreCase = true)) {
            return@withContext null
        }

        val baseId = normalizeNuvioMediaId(contentId).contentId.trim()
        if (baseId.isBlank()) {
            return@withContext null
        }

        val showKey = "series:$baseId"
        val showEntry = loadShowEntry(showKey = showKey, rawId = baseId) ?: return@withContext null

        val requestedSeason = seasonHint?.takeIf { it > 0 }
        val effectiveSeason =
            when {
                requestedSeason != null && showEntry.numberOfSeasons > 0 -> requestedSeason.coerceAtMost(showEntry.numberOfSeasons)
                requestedSeason != null -> requestedSeason
                showEntry.numberOfSeasons > 0 -> showEntry.numberOfSeasons
                else -> 1
            }

        val seasonsToFetch =
            buildList {
                add(effectiveSeason)
                if (requestedSeason != null && showEntry.numberOfSeasons > effectiveSeason) {
                    add(effectiveSeason + 1)
                }
            }.distinct()

        val out = mutableListOf<EpisodeInfo>()
        for (season in seasonsToFetch) {
            out += loadSeasonEpisodes(showEntry.tmdbId, showKey = showKey, seasonNumber = season)
        }

        out
            .distinctBy { "${it.season}:${it.episode}" }
            .sortedWith(compareBy({ it.season }, { it.episode }))
            .takeIf { it.isNotEmpty() }
    }

    private suspend fun loadShowEntry(showKey: String, rawId: String): ShowCacheEntry? {
        val cached = readCachedShowEntry(showKey)
        if (cached != null) return cached

        return transportSemaphore.withPermit {
            val cachedAgain = readCachedShowEntry(showKey)
            if (cachedAgain != null) return@withPermit cachedAgain

            val enrichmentResult =
                tmdbEnrichmentRepository.load(
                    rawId = rawId,
                    mediaTypeHint = MetadataLabMediaType.SERIES,
                    locale = localeProvider(),
                ) ?: return@withPermit null

            val enrichment = enrichmentResult.enrichment
            val seasons = (enrichment.titleDetails as? TmdbTvDetails)?.numberOfSeasons ?: 0
            val entry =
                ShowCacheEntry(
                    tmdbId = enrichment.tmdbId,
                    numberOfSeasons = seasons,
                    fetchedAtEpochMs = System.currentTimeMillis(),
                )
            showCache[showKey] = entry
            entry
        }
    }

    private fun readCachedShowEntry(showKey: String): ShowCacheEntry? {
        val cached = showCache[showKey] ?: return null
        if (System.currentTimeMillis() - cached.fetchedAtEpochMs > SHOW_CACHE_TTL_MS) {
            showCache.remove(showKey)
            return null
        }
        return cached
    }

    private suspend fun loadSeasonEpisodes(
        tmdbId: Int,
        showKey: String,
        seasonNumber: Int,
    ): List<EpisodeInfo> {
        if (tmdbId <= 0) return emptyList()
        if (seasonNumber <= 0) return emptyList()

        val cacheKey = "$showKey:season:$seasonNumber"
        val cached = readCachedSeasonEpisodes(cacheKey)
        if (cached != null) return cached

        return transportSemaphore.withPermit {
            val cachedAgain = readCachedSeasonEpisodes(cacheKey)
            if (cachedAgain != null) return@withPermit cachedAgain

            val episodes =
                tmdbEnrichmentRepository.loadSeasonEpisodes(
                    tmdbId = tmdbId,
                    seasonNumber = seasonNumber,
                    locale = localeProvider(),
                )

            val mapped =
                episodes.map { ep ->
                    EpisodeInfo(
                        season = ep.seasonNumber,
                        episode = ep.episodeNumber,
                        title = ep.name,
                        released = ep.airDate,
                    )
                }

            val entry =
                SeasonCacheEntry(
                    episodes = mapped,
                    fetchedAtEpochMs = System.currentTimeMillis(),
                )
            seasonCache[cacheKey] = entry
            mapped
        }
    }

    private fun readCachedSeasonEpisodes(cacheKey: String): List<EpisodeInfo>? {
        val cached = seasonCache[cacheKey] ?: return null
        if (System.currentTimeMillis() - cached.fetchedAtEpochMs > SEASON_CACHE_TTL_MS) {
            seasonCache.remove(cacheKey)
            return null
        }
        return cached.episodes
    }

    private companion object {
        private const val SHOW_CACHE_TTL_MS = 30 * 60 * 1000L
        private const val SEASON_CACHE_TTL_MS = 10 * 60 * 1000L
    }
}

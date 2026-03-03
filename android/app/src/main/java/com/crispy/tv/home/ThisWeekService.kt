package com.crispy.tv.home

import android.util.Log
import com.crispy.tv.player.EpisodeListProvider
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.WatchHistoryService
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class ThisWeekItem(
    val id: String,
    val seriesId: String,
    val seriesName: String,
    val episodeTitle: String?,
    val season: Int,
    val episode: Int,
    val episodeRange: String?,
    val releaseDate: String,
    val releasedAtMs: Long,
    val isReleased: Boolean,
    val posterUrl: String?,
    val type: String = "series",
)

class ThisWeekService(
    private val watchHistoryService: WatchHistoryService,
    private val episodeListProvider: EpisodeListProvider,
) {
    suspend fun loadThisWeek(nowMs: Long): ThisWeekResult {
        return try {
            val items = fetchThisWeekItems(nowMs)
            ThisWeekResult(items = items, statusMessage = null)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load This Week section", e)
            ThisWeekResult(items = emptyList(), statusMessage = null)
        }
    }

    private suspend fun fetchThisWeekItems(nowMs: Long): List<ThisWeekItem> {
        val seriesIds = gatherUserSeriesIds(nowMs)
        if (seriesIds.isEmpty()) return emptyList()

        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weekEnd = weekStart.plusDays(6)

        val allEpisodes = coroutineScope {
            seriesIds.map { (contentId, title, posterUrl) ->
                async {
                    try {
                        fetchThisWeekEpisodesForSeries(
                            contentId = contentId,
                            seriesName = title,
                            seriesPosterUrl = posterUrl,
                            weekStart = weekStart,
                            weekEnd = weekEnd,
                            zone = zone,
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "This Week: failed for $contentId ($title)", e)
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }

        val grouped = groupEpisodes(allEpisodes, nowMs)

        return grouped
            .sortedBy { it.releasedAtMs }
            .take(MAX_THIS_WEEK_ITEMS)
    }

    private suspend fun gatherUserSeriesIds(nowMs: Long): List<SeriesSeed> {
        val seen = linkedSetOf<String>()
        val result = mutableListOf<SeriesSeed>()

        try {
            val cached = watchHistoryService.getCachedContinueWatching(nowMs = nowMs)
            val cw = if (cached.entries.isNotEmpty()) cached else watchHistoryService.listContinueWatching(nowMs = nowMs)
            cw.entries
                .filter { it.contentType == MetadataLabMediaType.SERIES }
                .forEach { entry ->
                    if (seen.add(entry.contentId)) {
                        result.add(SeriesSeed(entry.contentId, entry.title, posterUrl = null))
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "This Week: failed to get CW entries", e)
        }

        try {
            val cachedLib = watchHistoryService.getCachedProviderLibrary()
            val lib = if (cachedLib.items.isNotEmpty()) cachedLib else watchHistoryService.listProviderLibrary()
            lib.items
                .filter { it.contentType == MetadataLabMediaType.SERIES }
                .forEach { item ->
                    if (seen.add(item.contentId)) {
                        result.add(SeriesSeed(item.contentId, item.title, item.posterUrl))
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "This Week: failed to get library entries", e)
        }

        return result.take(MAX_SERIES)
    }

    private suspend fun fetchThisWeekEpisodesForSeries(
        contentId: String,
        seriesName: String,
        seriesPosterUrl: String?,
        weekStart: LocalDate,
        weekEnd: LocalDate,
        zone: ZoneId,
    ): List<RawThisWeekEpisode> {
        val episodes = episodeListProvider.fetchEpisodeList("series", contentId, seasonHint = null) ?: return emptyList()

        return episodes.mapNotNull { ep ->
            if (ep.season == 0) return@mapNotNull null

            val releaseDateStr = ep.released?.trim()
            if (releaseDateStr.isNullOrEmpty()) return@mapNotNull null

            val releaseDate = try {
                Instant.parse(releaseDateStr).atZone(zone).toLocalDate()
            } catch (_: Exception) {
                try {
                    LocalDate.parse(releaseDateStr.take(10))
                } catch (_: Exception) {
                    return@mapNotNull null
                }
            }

            if (releaseDate.isBefore(weekStart) || releaseDate.isAfter(weekEnd)) {
                return@mapNotNull null
            }

            val releasedAtMs = releaseDate.atStartOfDay(zone).toInstant().toEpochMilli()

            RawThisWeekEpisode(
                seriesId = contentId,
                seriesName = seriesName,
                episodeTitle = ep.title,
                season = ep.season,
                episode = ep.episode,
                releaseDate = releaseDateStr,
                releasedAtMs = releasedAtMs,
                posterUrl = seriesPosterUrl,
            )
        }
    }

    private fun groupEpisodes(
        episodes: List<RawThisWeekEpisode>,
        nowMs: Long,
    ): List<ThisWeekItem> {
        data class GroupKey(val seriesId: String, val releaseDateDay: String)

        return episodes
            .groupBy { GroupKey(it.seriesId, it.releaseDate.take(10)) }
            .map { (_, group) ->
                val sorted = group.sortedBy { it.episode }
                val first = sorted.first()
                val last = sorted.last()
                val episodeRange = if (sorted.size > 1) {
                    "E${first.episode}-E${last.episode}"
                } else {
                    null
                }
                ThisWeekItem(
                    id = "${first.seriesId}:${first.season}:${episodeRange ?: first.episode}",
                    seriesId = first.seriesId,
                    seriesName = first.seriesName,
                    episodeTitle = first.episodeTitle,
                    season = first.season,
                    episode = first.episode,
                    episodeRange = episodeRange,
                    releaseDate = first.releaseDate,
                    releasedAtMs = first.releasedAtMs,
                    isReleased = first.releasedAtMs <= nowMs,
                    posterUrl = first.posterUrl,
                )
            }
    }

    private data class SeriesSeed(
        val contentId: String,
        val title: String,
        val posterUrl: String?,
    )

    private data class RawThisWeekEpisode(
        val seriesId: String,
        val seriesName: String,
        val episodeTitle: String?,
        val season: Int,
        val episode: Int,
        val releaseDate: String,
        val releasedAtMs: Long,
        val posterUrl: String?,
    )

    companion object {
        private const val TAG = "ThisWeekService"
        private const val MAX_SERIES = 300
        private const val MAX_THIS_WEEK_ITEMS = 20
    }
}

data class ThisWeekResult(
    val items: List<ThisWeekItem>,
    val statusMessage: String?,
)

package com.crispy.tv.home

import android.util.Log
import com.crispy.tv.domain.watch.AddonEpisodeInfo
import com.crispy.tv.player.ContinueWatchingEntry
import com.crispy.tv.player.EpisodeListProvider
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.ProviderLibraryItem
import com.crispy.tv.player.WatchHistoryService
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * An item in the "This Week" section on the home screen.
 * Mirrors Nuvio's ThisWeekSection item shape.
 */
data class ThisWeekItem(
    /** Unique display key (seriesId:season:episode or seriesId:season:episodeRange). */
    val id: String,
    /** IMDb ID of the series. */
    val seriesId: String,
    /** Display name of the series. */
    val seriesName: String,
    /** Episode title (or first episode title for groups). */
    val episodeTitle: String?,
    /** Season number. */
    val season: Int,
    /** Episode number (first episode if grouped). */
    val episode: Int,
    /**
     * For multi-episode groups airing same day/show: "E1-E3".
     * Null when there is only one episode.
     */
    val episodeRange: String?,
    /** ISO release date string. */
    val releaseDate: String,
    /** Release epoch millis for sorting. */
    val releasedAtMs: Long,
    /** Whether the episode has already aired. */
    val isReleased: Boolean,
    /** Poster URL — episode still not available from Cinemeta so fallback to series poster. */
    val posterUrl: String?,
    /** Type — always "series". */
    val type: String = "series",
)

/**
 * Loads "This Week" calendar episodes matching Nuvio's exact logic:
 * 1. Gather all user's series (CW + library, deduplicated, up to 300).
 * 2. For each series, fetch episode list from addon (via [EpisodeListProvider]).
 * 3. Filter episodes released within the current calendar week (Monday–Sunday).
 * 4. Exclude specials (season 0).
 * 5. Group episodes by series + release date (multi-episode groups).
 * 6. Sort by release date ascending, limit to 20.
 */
class ThisWeekService(
    private val watchHistoryService: WatchHistoryService,
    private val episodeListProvider: EpisodeListProvider,
) {
    /**
     * Fetches "This Week" items. Returns an empty list on any top-level failure,
     * and logs per-show errors without crashing the whole section.
     */
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
        // --- Step 1: Gather all the user's series (deduplicated by content ID) ---
        val seriesIds = gatherUserSeriesIds(nowMs)
        if (seriesIds.isEmpty()) return emptyList()

        // --- Step 2 & 3: Fetch episode lists and filter to this week ---
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weekEnd = weekStart.plusDays(6) // Sunday

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

        // --- Step 4: Group by series + release date ---
        val grouped = groupEpisodes(allEpisodes, nowMs)

        // --- Step 5: Sort by release date, limit to 20 ---
        return grouped
            .sortedBy { it.releasedAtMs }
            .take(MAX_THIS_WEEK_ITEMS)
    }

    /**
     * Gathers unique series IDs from CW entries and library, prioritizing CW.
     * Matches Nuvio: CW (highest) -> watchlist -> library -> recently watched.
     * In our case CW + library covers the same ground; limited to [MAX_SERIES].
     */
    private suspend fun gatherUserSeriesIds(nowMs: Long): List<SeriesSeed> {
        val seen = linkedSetOf<String>()
        val result = mutableListOf<SeriesSeed>()

        // Continue Watching shows (highest priority)
        try {
            val cw = watchHistoryService.getCachedContinueWatching(nowMs)
                ?: watchHistoryService.listContinueWatching(nowMs)
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

        // Library series (watchlist + collected)
        try {
            val lib = watchHistoryService.getCachedProviderLibrary(null)
                ?: watchHistoryService.listProviderLibrary(null)
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
        val episodes = episodeListProvider.fetchEpisodeList("series", contentId)
            ?: return emptyList()

        return episodes.mapNotNull { ep ->
            // Skip specials (season 0)
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

            // Filter: must be within this week (Monday–Sunday)
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

    /**
     * Groups episodes by (seriesId, releaseDate). If multiple episodes of the
     * same show air on the same day, merge into a single item with episodeRange.
     * Matches Nuvio's grouping logic.
     */
    private fun groupEpisodes(
        episodes: List<RawThisWeekEpisode>,
        nowMs: Long,
    ): List<ThisWeekItem> {
        // Group key: seriesId + releaseDate (date portion only, first 10 chars)
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

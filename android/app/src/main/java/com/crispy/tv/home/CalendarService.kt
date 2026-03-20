package com.crispy.tv.home

import android.util.Log
import androidx.compose.runtime.Immutable
import com.crispy.tv.domain.metadata.normalizeNuvioMediaId
import com.crispy.tv.metadata.tmdb.TmdbEnrichmentRepository
import com.crispy.tv.metadata.tmdb.TmdbSeasonEpisode
import com.crispy.tv.player.ContinueWatchingEntry
import com.crispy.tv.player.ContinueWatchingResult
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.ProviderLibraryItem
import com.crispy.tv.player.ProviderLibrarySnapshot
import com.crispy.tv.player.WatchedEpisodeRecord
import com.crispy.tv.player.WatchHistoryEntry
import com.crispy.tv.player.WatchHistoryService
import com.crispy.tv.player.WatchProvider
import com.crispy.tv.player.WatchProviderAuthState
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Immutable
data class CalendarEpisodeItem(
    val id: String,
    val seriesId: String,
    val seriesName: String,
    val episodeTitle: String?,
    val overview: String?,
    val season: Int,
    val episode: Int,
    val episodeRange: String?,
    val episodeCount: Int,
    val releaseDate: String,
    val releasedAtMs: Long,
    val isReleased: Boolean,
    val isGroup: Boolean,
    val posterUrl: String?,
    val backdropUrl: String?,
    val thumbnailUrl: String?,
    val watchedKeys: Set<String> = emptySet(),
    val type: String = "series",
)

@Immutable
data class CalendarSeriesItem(
    val id: String,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val sourceLabel: String?,
    val type: String = "series",
)

enum class CalendarSectionKey {
    THIS_WEEK,
    UPCOMING,
    RECENTLY_RELEASED,
    NO_SCHEDULED,
}

@Immutable
data class CalendarSection(
    val key: CalendarSectionKey,
    val title: String,
    val episodeItems: List<CalendarEpisodeItem> = emptyList(),
    val seriesItems: List<CalendarSeriesItem> = emptyList(),
)

@Immutable
data class CalendarSnapshot(
    val sections: List<CalendarSection>,
    val statusMessage: String? = null,
    val isError: Boolean = false,
)

@Immutable
data class ThisWeekResult(
    val items: List<CalendarEpisodeItem>,
    val statusMessage: String?,
    val isError: Boolean = false,
)

class CalendarService internal constructor(
    private val watchHistoryService: WatchHistoryService,
    private val tmdbEnrichmentRepository: TmdbEnrichmentRepository,
    private val metaEpisodeService: CalendarMetaEpisodeService,
) {
    suspend fun loadCalendar(nowMs: Long): CalendarSnapshot {
        return try {
            fetchCalendarSnapshot(nowMs)
        } catch (error: Exception) {
            Log.w(TAG, "Failed to load calendar", error)
            CalendarSnapshot(
                sections = emptyList(),
                statusMessage = "Unable to load your calendar right now.",
                isError = true,
            )
        }
    }

    suspend fun loadThisWeek(nowMs: Long): ThisWeekResult {
        val snapshot = loadCalendar(nowMs)
        val rawItems =
            snapshot.sections
                .firstOrNull { it.key == CalendarSectionKey.THIS_WEEK }
                ?.episodeItems
                .orEmpty()

        return ThisWeekResult(
            items = projectHomeThisWeekItems(rawItems, nowMs),
            statusMessage = snapshot.statusMessage,
            isError = snapshot.isError,
        )
    }

    private suspend fun fetchCalendarSnapshot(nowMs: Long): CalendarSnapshot {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val authState = watchHistoryService.authState()
        val selectedSource = preferredCalendarProvider(authState)

        val localHistory = watchHistoryService.listLocalHistory(limit = LOCAL_HISTORY_LIMIT).entries
        val providerLibrary = loadProviderLibrarySnapshot(selectedSource)
        val continueWatching = loadContinueWatchingSnapshot(selectedSource, nowMs)
        val watchedEpisodeKeys = buildWatchedEpisodeKeys(loadWatchedEpisodeRecords(selectedSource))

        val seeds =
            gatherSeriesSeeds(
                continueWatching = continueWatching.entries,
                providerItems = providerLibrary.items,
                localHistory = localHistory,
                source = selectedSource,
            )
        if (seeds.isEmpty()) {
            return CalendarSnapshot(sections = emptyList(), statusMessage = "No shows to track yet.")
        }

        val results =
            coroutineScope {
                val semaphore = Semaphore(SERIES_FETCH_CONCURRENCY)
                seeds.map { seed ->
                    async {
                        semaphore.withPermit {
                            runCatching { loadSeriesCalendar(seed = seed, nowMs = nowMs) }
                                .getOrElse { error ->
                                    Log.w(TAG, "Failed calendar series load for ${seed.id}", error)
                                    SeriesCalendarLoadResult(
                                        series =
                                            CalendarSeriesItem(
                                                id = seed.id,
                                                title = seed.title,
                                                posterUrl = seed.posterUrl,
                                                backdropUrl = seed.backdropUrl,
                                                sourceLabel = seed.sourceLabel,
                                            )
                                    )
                                }
                        }
                    }
                }.awaitAll()
            }

        val allEpisodes =
            results
                .flatMap { it.episodes }
                .sortedBy { it.releasedAtMs }
                .take(MAX_TOTAL_EPISODES)

        val thisWeekEpisodes =
            allEpisodes.filter { episode ->
                val releaseDate = epochMsToLocalDate(episode.releasedAtMs, zone)
                isThisWeek(releaseDate, today) && episode.watchedKeys.none(watchedEpisodeKeys::contains)
            }

        val upcomingEpisodes =
            allEpisodes.filter { episode ->
                val releaseDate = epochMsToLocalDate(episode.releasedAtMs, zone)
                episode.releasedAtMs > nowMs && !isThisWeek(releaseDate, today)
            }

        val recentEpisodes =
            allEpisodes.filter { episode ->
                val releaseDate = epochMsToLocalDate(episode.releasedAtMs, zone)
                episode.releasedAtMs < nowMs && !isThisWeek(releaseDate, today)
            }

        val noScheduledSeries =
            results
                .filter { it.episodes.isEmpty() }
                .map { it.series }
                .take(MAX_NO_SCHEDULED_ITEMS)

        val sections =
            buildList {
                if (thisWeekEpisodes.isNotEmpty()) {
                    add(CalendarSection(CalendarSectionKey.THIS_WEEK, "This Week", episodeItems = thisWeekEpisodes))
                }
                if (upcomingEpisodes.isNotEmpty()) {
                    add(CalendarSection(CalendarSectionKey.UPCOMING, "Upcoming", episodeItems = upcomingEpisodes))
                }
                if (recentEpisodes.isNotEmpty()) {
                    add(CalendarSection(CalendarSectionKey.RECENTLY_RELEASED, "Recently Released", episodeItems = recentEpisodes))
                }
                if (noScheduledSeries.isNotEmpty()) {
                    add(CalendarSection(CalendarSectionKey.NO_SCHEDULED, "Series with No Scheduled Episodes", seriesItems = noScheduledSeries))
                }
            }

        return CalendarSnapshot(
            sections = sections,
            statusMessage = if (sections.isEmpty()) "No upcoming episodes found right now." else null,
        )
    }

    private suspend fun loadProviderLibrarySnapshot(source: WatchProvider): ProviderLibrarySnapshot {
        if (source == WatchProvider.LOCAL) {
            return ProviderLibrarySnapshot(statusMessage = "")
        }

        val cached = watchHistoryService.getCachedProviderLibrary(limitPerFolder = PROVIDER_LIBRARY_LIMIT, source = source)
        return if (cached.items.isNotEmpty() || cached.folders.isNotEmpty()) cached
        else watchHistoryService.listProviderLibrary(limitPerFolder = PROVIDER_LIBRARY_LIMIT, source = source)
    }

    private suspend fun loadContinueWatchingSnapshot(
        source: WatchProvider,
        nowMs: Long,
    ): ContinueWatchingResult {
        val cached = watchHistoryService.getCachedContinueWatching(limit = CONTINUE_WATCHING_LIMIT, nowMs = nowMs, source = source)
        return if (cached.entries.isNotEmpty()) cached
        else watchHistoryService.listContinueWatching(limit = CONTINUE_WATCHING_LIMIT, nowMs = nowMs, source = source)
    }

    private suspend fun loadWatchedEpisodeRecords(source: WatchProvider): List<WatchedEpisodeRecord> {
        return watchHistoryService.listWatchedEpisodeRecords(source = source)
    }

    private fun gatherSeriesSeeds(
        continueWatching: List<ContinueWatchingEntry>,
        providerItems: List<ProviderLibraryItem>,
        localHistory: List<WatchHistoryEntry>,
        source: WatchProvider,
    ): List<SeriesSeed> {
        val seen = linkedSetOf<String>()
        val result = mutableListOf<SeriesSeed>()

        fun addSeed(
            contentId: String,
            title: String,
            posterUrl: String?,
            backdropUrl: String?,
            sourceLabel: String?,
            addonId: String? = null,
        ) {
            val normalized = normalizeSeriesKey(contentId)
            if (normalized.isBlank() || !seen.add(normalized)) return
            result += SeriesSeed(normalized, title.ifBlank { normalized }, posterUrl, backdropUrl, sourceLabel, addonId)
        }

        continueWatching
            .asSequence()
            .filter { it.contentType == MetadataLabMediaType.SERIES }
            .forEach { entry ->
                addSeed(entry.contentId, entry.title, null, null, "Continue Watching")
            }

        providerItems
            .asSequence()
            .filter {
                it.provider == source &&
                    it.contentType == MetadataLabMediaType.SERIES &&
                    it.folderId in watchlistFolderIds(source)
            }
            .sortedByDescending { it.addedAtEpochMs }
            .forEach { item ->
                addSeed(item.contentId, item.title, item.posterUrl, item.backdropUrl, "Watchlist")
            }

        providerItems
            .asSequence()
            .filter {
                it.provider == source &&
                    it.contentType == MetadataLabMediaType.SERIES &&
                    it.folderId in libraryFolderIds(source)
            }
            .sortedByDescending { it.addedAtEpochMs }
            .forEach { item ->
                addSeed(item.contentId, item.title, item.posterUrl, item.backdropUrl, "Library")
            }

        when (source) {
            WatchProvider.LOCAL -> {
                localHistory
                    .asSequence()
                    .filter { it.contentType == MetadataLabMediaType.SERIES }
                    .sortedByDescending { it.watchedAtEpochMs }
                    .distinctBy { normalizeSeriesKey(it.contentId) }
                    .take(RECENTLY_WATCHED_SERIES_LIMIT)
                    .forEach { entry ->
                        addSeed(entry.contentId, entry.title, null, null, "Recently Watched")
                    }
            }

            else -> {
                providerItems
                    .asSequence()
                    .filter {
                        it.provider == source &&
                            it.contentType == MetadataLabMediaType.SERIES &&
                            source.isWatchedFolder(it.folderId)
                    }
                    .sortedByDescending { it.addedAtEpochMs }
                    .distinctBy { normalizeSeriesKey(it.contentId) }
                    .take(RECENTLY_WATCHED_SERIES_LIMIT)
                    .forEach { item ->
                        addSeed(item.contentId, item.title, item.posterUrl, item.backdropUrl, "Recently Watched")
                    }
            }
        }

        return result.take(MAX_SERIES)
    }

    private suspend fun loadSeriesCalendar(
        seed: SeriesSeed,
        nowMs: Long,
    ): SeriesCalendarLoadResult {
        val enrichment =
            runCatching {
                tmdbEnrichmentRepository.load(
                    rawId = seed.id,
                    mediaTypeHint = MetadataLabMediaType.SERIES,
                    locale = Locale.getDefault(),
                )
            }.getOrNull()

        val fallbackDetails = enrichment?.fallbackDetails
        val resolvedSeriesId = fallbackDetails?.id ?: seed.id
        val watchKeys = buildSeriesWatchKeys(seed.id, resolvedSeriesId, fallbackDetails?.imdbId)

        val metaEpisodes =
            metaEpisodeService.getUpcomingEpisodes(
                seriesId = seed.id,
                nowMs = nowMs,
                daysBack = DAYS_BACK,
                daysAhead = DAYS_AHEAD,
                maxEpisodes = MAX_EPISODES_PER_SERIES,
                preferredAddonId = seed.addonId ?: fallbackDetails?.addonId,
            )

        val series =
            CalendarSeriesItem(
                id = resolvedSeriesId,
                title = fallbackDetails?.title ?: metaEpisodes?.seriesName ?: seed.title,
                posterUrl = fallbackDetails?.posterUrl ?: metaEpisodes?.posterUrl ?: seed.posterUrl,
                backdropUrl = fallbackDetails?.backdropUrl ?: metaEpisodes?.backdropUrl ?: seed.backdropUrl,
                sourceLabel = seed.sourceLabel,
            )

        val videos = metaEpisodes?.episodes.orEmpty()
        if (videos.isEmpty()) {
            return SeriesCalendarLoadResult(series = series)
        }

        val tmdbSeasonDetails =
            loadTmdbSeasonDetails(
                tmdbId = enrichment?.enrichment?.tmdbId,
                seasons = videos.map { it.season }.filter { it > 0 }.distinct().take(MAX_TMDB_SEASONS),
            )

        val episodes =
            videos.mapNotNull { video ->
                if (video.season <= 0 || video.episode <= 0) return@mapNotNull null
                val releasedAtMs = video.releasedAtMs.takeIf { it > 0L } ?: return@mapNotNull null
                val tmdbEpisode = tmdbSeasonDetails[video.season to video.episode]
                val releaseDate = (video.released ?: tmdbEpisode?.airDate)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                CalendarEpisodeItem(
                    id = "${series.id}:${video.season}:${video.episode}",
                    seriesId = series.id,
                    seriesName = series.title,
                    episodeTitle = tmdbEpisode?.name ?: video.title ?: "Episode ${video.episode}",
                    overview = tmdbEpisode?.overview ?: video.overview,
                    season = video.season,
                    episode = video.episode,
                    episodeRange = null,
                    episodeCount = 1,
                    releaseDate = releaseDate,
                    releasedAtMs = releasedAtMs,
                    isReleased = releasedAtMs <= nowMs,
                    isGroup = false,
                    posterUrl = series.posterUrl,
                    backdropUrl = series.backdropUrl,
                    thumbnailUrl = tmdbEpisode?.stillUrl ?: video.thumbnailUrl,
                    watchedKeys = watchKeys.mapTo(linkedSetOf()) { key -> "$key:${video.season}:${video.episode}" },
                )
            }

        return SeriesCalendarLoadResult(series = series, episodes = episodes)
    }

    private suspend fun loadTmdbSeasonDetails(
        tmdbId: Int?,
        seasons: List<Int>,
    ): Map<Pair<Int, Int>, TmdbSeasonEpisode> {
        if (tmdbId == null || seasons.isEmpty()) return emptyMap()

        return buildMap {
            seasons.forEach { season ->
                runCatching {
                    tmdbEnrichmentRepository.loadSeasonEpisodes(
                        tmdbId = tmdbId,
                        seasonNumber = season,
                        locale = Locale.getDefault(),
                    )
                }.getOrDefault(emptyList()).forEach { episode ->
                    put(episode.seasonNumber to episode.episodeNumber, episode)
                }
            }
        }
    }

    private fun buildWatchedEpisodeKeys(records: List<WatchedEpisodeRecord>): Set<String> {
        return records.mapTo(linkedSetOf()) { record ->
            "${normalizeSeriesKey(record.contentId)}:${record.season}:${record.episode}"
        }
    }

    private fun buildSeriesWatchKeys(vararg ids: String?): Set<String> {
        return ids.mapNotNullTo(linkedSetOf()) { id ->
            id?.takeIf { it.isNotBlank() }?.let(::normalizeSeriesKey)
        }
    }

    private fun projectHomeThisWeekItems(
        items: List<CalendarEpisodeItem>,
        nowMs: Long,
    ): List<CalendarEpisodeItem> {
        val rawItems = items.filter { it.season != 0 }.take(HOME_THIS_WEEK_RAW_LIMIT)

        return rawItems
            .groupBy { item -> "${item.seriesId}_${item.releaseDate.take(10)}" }
            .values
            .map { group ->
                val sorted = group.sortedBy { it.episode }
                val first = sorted.first()
                val last = sorted.last()
                if (sorted.size == 1) {
                    first
                } else {
                    first.copy(
                        id = "group_${first.seriesId}_${first.releaseDate.take(10)}",
                        episodeTitle = null,
                        overview = null,
                        episodeRange = "E${first.episode}-E${last.episode}",
                        episodeCount = sorted.size,
                        isGroup = true,
                        isReleased = first.releasedAtMs <= nowMs,
                        thumbnailUrl = sorted.firstNotNullOfOrNull { it.thumbnailUrl },
                    )
                }
            }
            .sortedBy { it.releasedAtMs }
            .take(HOME_THIS_WEEK_RENDER_LIMIT)
    }

    private fun isThisWeek(date: LocalDate, today: LocalDate): Boolean {
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        val weekEnd = weekStart.plusDays(6)
        return !date.isBefore(weekStart) && !date.isAfter(weekEnd)
    }

    private fun epochMsToLocalDate(epochMs: Long, zone: ZoneId): LocalDate {
        return Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    }

    private fun normalizeSeriesKey(contentId: String): String {
        return normalizeNuvioMediaId(contentId).contentId.lowercase(Locale.US)
    }

    private fun watchlistFolderIds(source: WatchProvider): Set<String> {
        return when (source) {
            WatchProvider.TRAKT -> setOf("watchlist")
            WatchProvider.SIMKL -> setOf("plantowatch")
            WatchProvider.LOCAL -> emptySet()
        }
    }

    private fun libraryFolderIds(source: WatchProvider): Set<String> {
        return when (source) {
            WatchProvider.TRAKT -> setOf("collection")
            WatchProvider.SIMKL -> setOf("watching")
            WatchProvider.LOCAL -> emptySet()
        }
    }

    private data class SeriesSeed(
        val id: String,
        val title: String,
        val posterUrl: String?,
        val backdropUrl: String?,
        val sourceLabel: String?,
        val addonId: String?,
    )

    private data class SeriesCalendarLoadResult(
        val series: CalendarSeriesItem,
        val episodes: List<CalendarEpisodeItem> = emptyList(),
    )

    companion object {
        private const val TAG = "CalendarService"
        private const val CONTINUE_WATCHING_LIMIT = 80
        private const val LOCAL_HISTORY_LIMIT = 1000
        private const val PROVIDER_LIBRARY_LIMIT = 1000
        private const val MAX_SERIES = 300
        private const val MAX_EPISODES_PER_SERIES = 50
        private const val MAX_TMDB_SEASONS = 3
        private const val MAX_TOTAL_EPISODES = 500
        private const val MAX_NO_SCHEDULED_ITEMS = 20
        private const val RECENTLY_WATCHED_SERIES_LIMIT = 20
        private const val DAYS_BACK = 90
        private const val DAYS_AHEAD = 60
        private const val SERIES_FETCH_CONCURRENCY = 4
        private const val HOME_THIS_WEEK_RAW_LIMIT = 60
        private const val HOME_THIS_WEEK_RENDER_LIMIT = 20
    }
}

private fun preferredCalendarProvider(authState: WatchProviderAuthState): WatchProvider {
    return when {
        authState.traktAuthenticated -> WatchProvider.TRAKT
        authState.simklAuthenticated -> WatchProvider.SIMKL
        else -> WatchProvider.LOCAL
    }
}

private fun WatchProvider.isWatchedFolder(folderId: String): Boolean {
    return when (this) {
        WatchProvider.TRAKT -> folderId == "watched"
        WatchProvider.SIMKL -> folderId.startsWith("completed")
        WatchProvider.LOCAL -> false
    }
}

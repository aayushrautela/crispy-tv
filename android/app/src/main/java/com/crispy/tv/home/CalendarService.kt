package com.crispy.tv.home

import android.util.Log
import com.crispy.tv.domain.metadata.normalizeNuvioMediaId
import com.crispy.tv.metadata.tmdb.TmdbEnrichmentRepository
import com.crispy.tv.metadata.tmdb.TmdbTvDetails
import com.crispy.tv.player.ContinueWatchingEntry
import com.crispy.tv.player.ContinueWatchingResult
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.player.ProviderLibraryItem
import com.crispy.tv.player.ProviderLibrarySnapshot
import com.crispy.tv.player.WatchHistoryEntry
import com.crispy.tv.player.WatchHistoryService
import com.crispy.tv.player.WatchProvider
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
    val type: String = "series",
)

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

data class CalendarSection(
    val key: CalendarSectionKey,
    val title: String,
    val episodeItems: List<CalendarEpisodeItem> = emptyList(),
    val seriesItems: List<CalendarSeriesItem> = emptyList(),
)

data class CalendarSnapshot(
    val sections: List<CalendarSection>,
    val statusMessage: String? = null,
)

data class ThisWeekResult(
    val items: List<CalendarEpisodeItem>,
    val statusMessage: String?,
)

class CalendarService(
    private val watchHistoryService: WatchHistoryService,
    private val tmdbEnrichmentRepository: TmdbEnrichmentRepository,
) {
    suspend fun loadCalendar(nowMs: Long): CalendarSnapshot {
        return try {
            fetchCalendarSnapshot(nowMs)
        } catch (error: Exception) {
            Log.w(TAG, "Failed to load calendar", error)
            CalendarSnapshot(
                sections = emptyList(),
                statusMessage = "Unable to load your calendar right now.",
            )
        }
    }

    suspend fun loadThisWeek(nowMs: Long): ThisWeekResult {
        val snapshot = loadCalendar(nowMs)
        val items =
            snapshot.sections
                .firstOrNull { it.key == CalendarSectionKey.THIS_WEEK }
                ?.episodeItems
                .orEmpty()
        return ThisWeekResult(items = items, statusMessage = snapshot.statusMessage)
    }

    private suspend fun fetchCalendarSnapshot(nowMs: Long): CalendarSnapshot {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weekEnd = weekStart.plusDays(6)
        val recentWindowStart = today.minusDays(RECENT_WINDOW_DAYS.toLong())
        val upcomingWindowEnd = today.plusDays(UPCOMING_WINDOW_DAYS.toLong())

        val authState = watchHistoryService.authState()
        val selectedSource = preferredCalendarProvider(authState)
        val localHistory = watchHistoryService.listLocalHistory(limit = LOCAL_HISTORY_LIMIT).entries
        val providerLibrary = loadProviderLibrarySnapshot(selectedSource)
        val continueWatching = loadContinueWatchingSnapshot(selectedSource, nowMs)
        val watchedEpisodeKeys =
            buildWatchedEpisodeKeys(
                localHistory = localHistory,
                providerItems = providerLibrary.items,
                source = selectedSource,
            )

        val seeds =
            gatherSeriesSeeds(
                continueWatching = continueWatching.entries,
                providerItems = providerLibrary.items,
                localHistory = localHistory,
                source = selectedSource,
            )
        if (seeds.isEmpty()) {
            return CalendarSnapshot(statusMessage = "No shows to track yet.", sections = emptyList())
        }

        val results =
            coroutineScope {
                val semaphore = Semaphore(SERIES_FETCH_CONCURRENCY)
                seeds.map { seed ->
                    async {
                        semaphore.withPermit {
                            loadSeriesCalendar(
                                seed = seed,
                                zone = zone,
                                nowMs = nowMs,
                                windowStart = recentWindowStart,
                                windowEnd = upcomingWindowEnd,
                                watchedEpisodeKeys = watchedEpisodeKeys,
                            )
                        }
                    }
                }.awaitAll()
            }

        val thisWeekEpisodes =
            results.flatMap { result ->
                result.episodes.filter { episode ->
                    val releasedDate = epochMsToLocalDate(episode.releasedAtMs, zone)
                    !releasedDate.isBefore(weekStart) && !releasedDate.isAfter(weekEnd)
                }
            }
        val upcomingEpisodes =
            results.flatMap { result ->
                result.episodes.filter { episode ->
                    val releasedDate = epochMsToLocalDate(episode.releasedAtMs, zone)
                    releasedDate.isAfter(weekEnd)
                }
            }
        val recentlyReleasedEpisodes =
            results.flatMap { result ->
                result.episodes.filter { episode ->
                    val releasedDate = epochMsToLocalDate(episode.releasedAtMs, zone)
                    releasedDate.isBefore(weekStart)
                }
            }

        val thisWeek = groupEpisodes(thisWeekEpisodes, nowMs)
        val upcoming = groupEpisodes(upcomingEpisodes, nowMs)
        val recentlyReleased = groupEpisodes(recentlyReleasedEpisodes, nowMs)

        val noScheduledEpisodes =
            results
                .filterNot { it.hasScheduledEpisodesInWindow }
                .map { result ->
                    CalendarSeriesItem(
                        id = result.series.id,
                        title = result.series.title,
                        posterUrl = result.series.posterUrl,
                        backdropUrl = result.series.backdropUrl,
                        sourceLabel = result.series.sourceLabel,
                    )
                }
                .take(MAX_NO_SCHEDULED_ITEMS)

        val sections =
            buildList {
                if (thisWeek.isNotEmpty()) {
                    add(
                        CalendarSection(
                            key = CalendarSectionKey.THIS_WEEK,
                            title = "This Week",
                            episodeItems = thisWeek,
                        )
                    )
                }
                if (upcoming.isNotEmpty()) {
                    add(
                        CalendarSection(
                            key = CalendarSectionKey.UPCOMING,
                            title = "Upcoming",
                            episodeItems = upcoming,
                        )
                    )
                }
                if (recentlyReleased.isNotEmpty()) {
                    add(
                        CalendarSection(
                            key = CalendarSectionKey.RECENTLY_RELEASED,
                            title = "Recently Released",
                            episodeItems = recentlyReleased,
                        )
                    )
                }
                if (noScheduledEpisodes.isNotEmpty()) {
                    add(
                        CalendarSection(
                            key = CalendarSectionKey.NO_SCHEDULED,
                            title = "Series with No Scheduled Episodes",
                            seriesItems = noScheduledEpisodes,
                        )
                    )
                }
            }

        val statusMessage =
            if (sections.isEmpty()) {
                "No upcoming episodes found right now."
            } else {
                null
            }

        return CalendarSnapshot(sections = sections, statusMessage = statusMessage)
    }

    private suspend fun loadProviderLibrarySnapshot(source: WatchProvider): ProviderLibrarySnapshot {
        if (source == WatchProvider.LOCAL) {
            return ProviderLibrarySnapshot(statusMessage = "")
        }

        val cached = watchHistoryService.getCachedProviderLibrary(limitPerFolder = PROVIDER_LIBRARY_LIMIT, source = source)
        return if (cached.items.isNotEmpty() || cached.folders.isNotEmpty()) {
            cached
        } else {
            watchHistoryService.listProviderLibrary(limitPerFolder = PROVIDER_LIBRARY_LIMIT, source = source)
        }
    }

    private suspend fun loadContinueWatchingSnapshot(
        source: WatchProvider,
        nowMs: Long,
    ): ContinueWatchingResult {
        val cached = watchHistoryService.getCachedContinueWatching(limit = CONTINUE_WATCHING_LIMIT, nowMs = nowMs, source = source)
        return if (cached.entries.isNotEmpty()) {
            cached
        } else {
            watchHistoryService.listContinueWatching(limit = CONTINUE_WATCHING_LIMIT, nowMs = nowMs, source = source)
        }
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
        ) {
            val normalized = normalizeSeriesKey(contentId)
            if (normalized.isBlank() || !seen.add(normalized)) return
            result +=
                SeriesSeed(
                    id = contentId,
                    title = title,
                    posterUrl = posterUrl,
                    backdropUrl = backdropUrl,
                    sourceLabel = sourceLabel,
                )
        }

        continueWatching
            .filter { it.contentType == MetadataLabMediaType.SERIES }
            .forEach { entry ->
                addSeed(
                    contentId = entry.contentId,
                    title = entry.title,
                    posterUrl = null,
                    backdropUrl = null,
                    sourceLabel = "Continue Watching",
                )
            }

        providerItems
            .asSequence()
            .filter { item ->
                item.provider == source &&
                    item.contentType == MetadataLabMediaType.SERIES &&
                    item.folderId in watchlistFolderIds(source)
            }
            .sortedByDescending { it.addedAtEpochMs }
            .forEach { item ->
                addSeed(
                    contentId = item.contentId,
                    title = item.title,
                    posterUrl = item.posterUrl,
                    backdropUrl = item.backdropUrl,
                    sourceLabel = "Watchlist",
                )
            }

        providerItems
            .asSequence()
            .filter { item ->
                item.provider == source &&
                    item.contentType == MetadataLabMediaType.SERIES &&
                    item.folderId in libraryFolderIds(source)
            }
            .sortedByDescending { it.addedAtEpochMs }
            .forEach { item ->
                addSeed(
                    contentId = item.contentId,
                    title = item.title,
                    posterUrl = item.posterUrl,
                    backdropUrl = item.backdropUrl,
                    sourceLabel = "Library",
                )
            }

        providerItems
            .asSequence()
            .filter { item ->
                item.provider == source &&
                    item.contentType == MetadataLabMediaType.SERIES &&
                    source.isWatchedFolder(item.folderId)
            }
            .sortedByDescending { it.addedAtEpochMs }
            .forEach { item ->
                addSeed(
                    contentId = item.contentId,
                    title = item.title,
                    posterUrl = item.posterUrl,
                    backdropUrl = item.backdropUrl,
                    sourceLabel = "Recently Watched",
                )
            }

        localHistory
            .asSequence()
            .filter { it.contentType == MetadataLabMediaType.SERIES }
            .sortedByDescending { it.watchedAtEpochMs }
            .forEach { entry ->
                addSeed(
                    contentId = entry.contentId,
                    title = entry.title,
                    posterUrl = null,
                    backdropUrl = null,
                    sourceLabel = "Recently Watched",
                )
            }

        return result.take(MAX_SERIES)
    }

    private suspend fun loadSeriesCalendar(
        seed: SeriesSeed,
        zone: ZoneId,
        nowMs: Long,
        windowStart: LocalDate,
        windowEnd: LocalDate,
        watchedEpisodeKeys: Set<String>,
    ): SeriesCalendarLoadResult {
        val enriched =
            tmdbEnrichmentRepository.load(
                rawId = seed.id,
                mediaTypeHint = MetadataLabMediaType.SERIES,
                locale = Locale.getDefault(),
            )

        val fallbackDetails = enriched?.fallbackDetails
        val tvDetails = enriched?.enrichment?.titleDetails as? TmdbTvDetails
        val tmdbId = enriched?.enrichment?.tmdbId

        val series =
            CalendarSeriesItem(
                id = fallbackDetails?.id ?: seed.id,
                title = fallbackDetails?.title ?: seed.title,
                posterUrl = fallbackDetails?.posterUrl ?: seed.posterUrl,
                backdropUrl = fallbackDetails?.backdropUrl ?: seed.backdropUrl,
                sourceLabel = seed.sourceLabel,
            )

        if (tvDetails == null || tmdbId == null || tvDetails.numberOfSeasons <= 0) {
            return SeriesCalendarLoadResult(series = series)
        }

        val seasonsToFetch =
            (maxOf(1, tvDetails.numberOfSeasons - (MAX_SEASONS_PER_SERIES - 1))..tvDetails.numberOfSeasons).toList()

        var hasScheduledEpisodesInWindow = false
        val episodes =
            seasonsToFetch.flatMap { seasonNumber ->
                tmdbEnrichmentRepository.loadSeasonEpisodes(
                    tmdbId = tmdbId,
                    seasonNumber = seasonNumber,
                    locale = Locale.getDefault(),
                ).mapNotNull { episode ->
                    if (episode.seasonNumber == 0) return@mapNotNull null

                    val releaseDate = parseReleaseDate(episode.airDate, zone) ?: return@mapNotNull null
                    if (releaseDate.isBefore(windowStart) || releaseDate.isAfter(windowEnd)) {
                        return@mapNotNull null
                    }
                    hasScheduledEpisodesInWindow = true

                    val isWatched =
                        episodeIsWatched(
                            watchedEpisodeKeys = watchedEpisodeKeys,
                            candidateIds = buildSeriesCandidateIds(seed.id, fallbackDetails?.id, fallbackDetails?.imdbId),
                            season = episode.seasonNumber,
                            episode = episode.episodeNumber,
                            playbackIdentity =
                                PlaybackIdentity(
                                    imdbId = fallbackDetails?.imdbId,
                                    tmdbId = tmdbId,
                                    contentType = MetadataLabMediaType.SERIES,
                                    season = episode.seasonNumber,
                                    episode = episode.episodeNumber,
                                    title = fallbackDetails?.title ?: seed.title,
                                    year = fallbackDetails?.year,
                                    showTitle = fallbackDetails?.title ?: seed.title,
                                    showYear = fallbackDetails?.year,
                                ),
                        )
                    if (isWatched) return@mapNotNull null

                    val releasedAtMs = releaseDate.atStartOfDay(zone).toInstant().toEpochMilli()
                    RawCalendarEpisode(
                        seriesId = series.id,
                        seriesName = series.title,
                        episodeTitle = episode.name,
                        overview = episode.overview,
                        season = episode.seasonNumber,
                        episode = episode.episodeNumber,
                        releaseDate = episode.airDate,
                        releasedAtMs = releasedAtMs,
                        posterUrl = series.posterUrl,
                        backdropUrl = series.backdropUrl,
                        thumbnailUrl = episode.stillUrl,
                    )
                }
            }

        return SeriesCalendarLoadResult(
            series = series,
            hasScheduledEpisodesInWindow = hasScheduledEpisodesInWindow,
            episodes = episodes,
        )
    }

    private suspend fun episodeIsWatched(
        watchedEpisodeKeys: Set<String>,
        candidateIds: Set<String>,
        season: Int,
        episode: Int,
        playbackIdentity: PlaybackIdentity,
    ): Boolean {
        val keyMatches = candidateIds.any { candidateId -> watchedEpisodeKeys.contains("$candidateId:$season:$episode") }
        if (keyMatches) return true

        val localProgress = watchHistoryService.getLocalWatchProgress(playbackIdentity)
        return localProgress?.progressPercent?.let { it >= WATCHED_PROGRESS_PERCENT } == true
    }

    private fun buildWatchedEpisodeKeys(
        localHistory: List<WatchHistoryEntry>,
        providerItems: List<ProviderLibraryItem>,
        source: WatchProvider,
    ): Set<String> {
        val result = linkedSetOf<String>()

        localHistory.forEach { entry ->
            val season = entry.season ?: return@forEach
            val episode = entry.episode ?: return@forEach
            result += "${normalizeSeriesKey(entry.contentId)}:$season:$episode"
        }

        providerItems.forEach { item ->
            if (item.provider != source || !source.isWatchedFolder(item.folderId)) return@forEach
            val season = item.season ?: return@forEach
            val episode = item.episode ?: return@forEach
            result += "${normalizeSeriesKey(item.contentId)}:$season:$episode"
        }

        return result
    }

    private fun groupEpisodes(
        episodes: List<RawCalendarEpisode>,
        nowMs: Long,
    ): List<CalendarEpisodeItem> {
        data class GroupKey(val seriesId: String, val season: Int, val releaseDay: String)

        return episodes
            .sortedBy { it.releasedAtMs }
            .groupBy { GroupKey(it.seriesId, it.season, it.releaseDate.take(10)) }
            .values
            .map { group ->
                val sorted = group.sortedBy { it.episode }
                val first = sorted.first()
                val last = sorted.last()
                val isGroup = sorted.size > 1
                val episodeRange = if (isGroup) "E${first.episode}-E${last.episode}" else null
                CalendarEpisodeItem(
                    id = "${first.seriesId}:${first.season}:${episodeRange ?: first.episode}",
                    seriesId = first.seriesId,
                    seriesName = first.seriesName,
                    episodeTitle = if (isGroup) null else first.episodeTitle,
                    overview = if (isGroup) null else first.overview,
                    season = first.season,
                    episode = first.episode,
                    episodeRange = episodeRange,
                    episodeCount = sorted.size,
                    releaseDate = first.releaseDate,
                    releasedAtMs = first.releasedAtMs,
                    isReleased = first.releasedAtMs <= nowMs,
                    isGroup = isGroup,
                    posterUrl = first.posterUrl,
                    backdropUrl = first.backdropUrl,
                    thumbnailUrl = sorted.firstNotNullOfOrNull { it.thumbnailUrl },
                )
            }
            .take(MAX_SECTION_ITEMS)
    }

    private fun buildSeriesCandidateIds(
        seedId: String,
        resolvedId: String?,
        imdbId: String?,
    ): Set<String> {
        return buildSet {
            add(normalizeSeriesKey(seedId))
            resolvedId?.let { add(normalizeSeriesKey(it)) }
            imdbId?.let { add(normalizeSeriesKey(it)) }
        }
    }

    private fun parseReleaseDate(value: String?, zone: ZoneId): LocalDate? {
        val rawValue = value?.trim().orEmpty()
        if (rawValue.isEmpty()) return null
        return try {
            Instant.parse(rawValue).atZone(zone).toLocalDate()
        } catch (_: Exception) {
            try {
                LocalDate.parse(rawValue.take(10))
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun epochMsToLocalDate(
        epochMs: Long,
        zone: ZoneId,
    ): LocalDate {
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
    )

    private data class RawCalendarEpisode(
        val seriesId: String,
        val seriesName: String,
        val episodeTitle: String?,
        val overview: String?,
        val season: Int,
        val episode: Int,
        val releaseDate: String,
        val releasedAtMs: Long,
        val posterUrl: String?,
        val backdropUrl: String?,
        val thumbnailUrl: String?,
    )

    private data class SeriesCalendarLoadResult(
        val series: CalendarSeriesItem,
        val hasScheduledEpisodesInWindow: Boolean = false,
        val episodes: List<RawCalendarEpisode> = emptyList(),
    )

    companion object {
        private const val TAG = "CalendarService"
        private const val CONTINUE_WATCHING_LIMIT = 80
        private const val LOCAL_HISTORY_LIMIT = 1000
        private const val PROVIDER_LIBRARY_LIMIT = 1000
        private const val MAX_SERIES = 300
        private const val MAX_SEASONS_PER_SERIES = 3
        private const val RECENT_WINDOW_DAYS = 30
        private const val UPCOMING_WINDOW_DAYS = 60
        private const val MAX_SECTION_ITEMS = 20
        private const val MAX_NO_SCHEDULED_ITEMS = 20
        private const val WATCHED_PROGRESS_PERCENT = 85.0
        private const val SERIES_FETCH_CONCURRENCY = 4
    }
}

private fun preferredCalendarProvider(authState: com.crispy.tv.player.WatchProviderAuthState): WatchProvider {
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

package com.crispy.tv.home

import android.util.Log
import androidx.compose.runtime.Immutable
import com.crispy.tv.backend.BackendContext
import com.crispy.tv.backend.BackendContextResolver
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.player.MetadataLabMediaType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale

@Immutable
data class CalendarEpisodeItem(
    val id: String,
    val mediaKey: String,
    val localKey: String,
    val highlightEpisodeId: String? = null,
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
    val absoluteEpisodeNumber: Int? = null,
)

@Immutable
data class CalendarSeriesItem(
    val id: String,
    val mediaKey: String,
    val localKey: String,
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
    private val backendClient: CrispyBackendClient,
    private val backendContextResolver: BackendContextResolver,
) {
    @Volatile
    private var cachedCalendarSnapshot: CachedCalendarSnapshot? = null

    suspend fun loadCalendar(nowMs: Long): CalendarSnapshot {
        val backendContext = getBackendContext()
        val cachedSnapshot =
            backendContext
                ?.let { context -> cachedCalendarSnapshot?.takeIf { it.profileId == context.profileId }?.snapshot }
        return try {
            val freshSnapshot = fetchCalendarSnapshot(nowMs, backendContext)
            if (!freshSnapshot.isError) {
                backendContext?.let { context ->
                    cachedCalendarSnapshot = CachedCalendarSnapshot(profileId = context.profileId, snapshot = freshSnapshot)
                }
            }
            freshSnapshot
        } catch (error: Exception) {
            Log.w(TAG, "Failed to load calendar", error)
            cachedSnapshot
                ?: CalendarSnapshot(
                    sections = emptyList(),
                    statusMessage = "Unable to load your calendar right now.",
                    isError = true,
                )
        }
    }

    suspend fun loadThisWeek(nowMs: Long): ThisWeekResult {
        return try {
            fetchThisWeekResult(nowMs)
        } catch (error: Exception) {
            Log.w(TAG, "Failed to load this week", error)
            ThisWeekResult(
                items = emptyList(),
                statusMessage = "Unable to load this week right now.",
                isError = true,
            )
        }
    }

    private suspend fun fetchCalendarSnapshot(
        nowMs: Long,
        backendContext: BackendContext? = null,
    ): CalendarSnapshot {
        val resolvedBackendContext = backendContext ?: getBackendContext()
            ?: return CalendarSnapshot(
                sections = emptyList(),
                statusMessage = "Sign in and select a profile to load your calendar.",
                isError = true,
            )

        val response = backendClient.getCalendar(
            accessToken = resolvedBackendContext.accessToken,
            profileId = resolvedBackendContext.profileId,
        )

        val sections = response.toCalendarSections(nowMs)
        return CalendarSnapshot(
            sections = sections,
            statusMessage = if (sections.isEmpty()) "No upcoming episodes found right now." else null,
            isError = false,
        )
    }

    private suspend fun fetchThisWeekResult(nowMs: Long): ThisWeekResult {
        val backendContext = getBackendContext()
            ?: return ThisWeekResult(
                items = emptyList(),
                statusMessage = "Sign in and select a profile to load this week.",
                isError = true,
            )

        val response = backendClient.getCalendarThisWeek(
            accessToken = backendContext.accessToken,
            profileId = backendContext.profileId,
        )
        val rawItems =
            response.items
                .mapNotNull { it.toCalendarEpisodeItem(nowMs) }
                .sortedBy { it.releasedAtMs }

        val projected = projectHomeThisWeekItems(rawItems, nowMs)
        return ThisWeekResult(
            items = projected,
            statusMessage = if (projected.isEmpty()) "No episodes airing this week right now." else null,
            isError = false,
        )
    }

    private suspend fun getBackendContext(): BackendContext? {
        return backendContextResolver.resolve()
    }

    private fun CrispyBackendClient.CalendarResponse.toCalendarSections(nowMs: Long): List<CalendarSection> {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val itemsByBucket = items.groupBy { it.bucket.trim().lowercase(Locale.US) }

        val thisWeekEpisodes =
            itemsByBucket["this_week"].orEmpty()
                .mapNotNull { it.toCalendarEpisodeItem(nowMs) }
                .sortedBy { it.releasedAtMs }

        val upcomingEpisodes =
            itemsByBucket["upcoming"].orEmpty()
                .mapNotNull { it.toCalendarEpisodeItem(nowMs) }
                .sortedBy { it.releasedAtMs }

        val recentEpisodes =
            (itemsByBucket["recently_released"].orEmpty() + itemsByBucket["up_next"].orEmpty())
                .mapNotNull { it.toCalendarEpisodeItem(nowMs) }
                .distinctBy { it.localKey }
                .sortedBy { it.releasedAtMs }

        val noScheduledSeries =
            itemsByBucket["no_scheduled"].orEmpty()
                .map { it.toCalendarSeriesItem() }
                .distinctBy { it.localKey }

        return buildList {
            if (thisWeekEpisodes.isNotEmpty()) {
                add(CalendarSection(CalendarSectionKey.THIS_WEEK, "This Week", episodeItems = thisWeekEpisodes))
            }
            if (upcomingEpisodes.isNotEmpty()) {
                add(CalendarSection(CalendarSectionKey.UPCOMING, "Upcoming", episodeItems = upcomingEpisodes))
            }
            if (recentEpisodes.isNotEmpty()) {
                val filtered = recentEpisodes.filter { episode ->
                    val releaseDate = epochMsToLocalDate(episode.releasedAtMs, zone)
                    !isThisWeek(releaseDate, today)
                }
                if (filtered.isNotEmpty()) {
                    add(CalendarSection(CalendarSectionKey.RECENTLY_RELEASED, "Recently Released", episodeItems = filtered))
                }
            }
            if (noScheduledSeries.isNotEmpty()) {
                add(CalendarSection(CalendarSectionKey.NO_SCHEDULED, "Series with No Scheduled Episodes", seriesItems = noScheduledSeries))
            }
        }
    }

    private fun CrispyBackendClient.CalendarItem.toCalendarEpisodeItem(nowMs: Long): CalendarEpisodeItem? {
        val mediaType = media.mediaType.trim().lowercase(Locale.US)
        if (mediaType != "episode") return null
        val season = media.seasonNumber ?: return null
        val episode = media.episodeNumber ?: return null
        val releaseDate = media.airDate?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        val releasedAtMs = parseCalendarReleaseToEpochMs(releaseDate) ?: return null
        val watchedKey = "${media.provider.lowercase(Locale.US)}:${media.providerId.lowercase(Locale.US)}:$season:$episode"
        val localKey = "${media.provider}:${media.providerId}:$season:$episode"
        return CalendarEpisodeItem(
            id = localKey,
            mediaKey = media.mediaKey,
            localKey = localKey,
            highlightEpisodeId = null,
            seriesName = media.title,
            episodeTitle = media.episodeTitle,
            overview = media.subtitle,
            season = season,
            episode = episode,
            episodeRange = null,
            episodeCount = 1,
            releaseDate = releaseDate,
            releasedAtMs = releasedAtMs,
            isReleased = releasedAtMs <= nowMs,
            isGroup = false,
            posterUrl = media.posterUrl,
            backdropUrl = media.backdropUrl,
            thumbnailUrl = media.backdropUrl,
            watchedKeys = setOf(watchedKey),
            absoluteEpisodeNumber = null,
        )
    }

    private fun CrispyBackendClient.CalendarItem.toCalendarSeriesItem(): CalendarSeriesItem {
        val localKey = "${media.provider}:${media.providerId}"
        return CalendarSeriesItem(
            id = localKey,
            mediaKey = media.mediaKey,
            localKey = localKey,
            title = media.title,
            posterUrl = media.posterUrl,
            backdropUrl = media.backdropUrl,
            sourceLabel = null,
        )
    }

    private fun projectHomeThisWeekItems(
        items: List<CalendarEpisodeItem>,
        nowMs: Long,
    ): List<CalendarEpisodeItem> {
        val rawItems = items.filter { it.season != 0 }.take(HOME_THIS_WEEK_RAW_LIMIT)

        return rawItems
            .groupBy { item -> "${item.mediaKey}_${item.releaseDate.take(10)}" }
            .values
            .map { group ->
                val sorted = group.sortedBy { it.episode }
                val first = sorted.first()
                val last = sorted.last()
                if (sorted.size == 1) {
                    first
                } else {
                    val groupedLocalKey = "group_${first.mediaKey}_${first.releaseDate.take(10)}"
                    first.copy(
                        id = groupedLocalKey,
                        localKey = groupedLocalKey,
                        highlightEpisodeId = null,
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

    private fun parseCalendarReleaseToEpochMs(raw: String?): Long? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        return runCatching { Instant.parse(value).toEpochMilli() }
            .recoverCatching {
                LocalDate.parse(value.take(10))
                    .atStartOfDay(java.time.ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli()
            }
            .getOrNull()
    }

    companion object {
        private const val TAG = "CalendarService"
        private const val HOME_THIS_WEEK_RAW_LIMIT = 60
        private const val HOME_THIS_WEEK_RENDER_LIMIT = 20
    }

    private data class CachedCalendarSnapshot(
        val profileId: String,
        val snapshot: CalendarSnapshot,
    )
}

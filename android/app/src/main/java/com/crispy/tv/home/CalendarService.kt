package com.crispy.tv.home

import android.util.Log
import androidx.compose.runtime.Immutable
import com.crispy.tv.accounts.ActiveProfileStore
import com.crispy.tv.accounts.SupabaseAccountClient
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
    private val supabaseAccountClient: SupabaseAccountClient,
    private val activeProfileStore: ActiveProfileStore,
    private val backendClient: CrispyBackendClient,
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
        val backendContext = getBackendContext()
            ?: return CalendarSnapshot(
                sections = emptyList(),
                statusMessage = "Sign in and select a profile to load your calendar.",
                isError = true,
            )

        val response = backendClient.getCalendar(
            accessToken = backendContext.accessToken,
            profileId = backendContext.profileId,
        )

        val sections = response.toCalendarSections(nowMs)
        return CalendarSnapshot(
            sections = sections,
            statusMessage = if (sections.isEmpty()) "No upcoming episodes found right now." else null,
            isError = false,
        )
    }

    private suspend fun getBackendContext(): BackendContext? {
        if (!supabaseAccountClient.isConfigured() || !backendClient.isConfigured()) {
            return null
        }
        val session = supabaseAccountClient.ensureValidSession() ?: return null
        var profileId = activeProfileStore.getActiveProfileId(session.userId).orEmpty().trim()
        if (profileId.isBlank()) {
            profileId = runCatching {
                backendClient.getMe(session.accessToken).profiles.firstOrNull()?.id.orEmpty().trim()
            }.getOrDefault("")
            if (profileId.isNotBlank()) {
                activeProfileStore.setActiveProfileId(session.userId, profileId)
            }
        }
        if (profileId.isBlank()) {
            return null
        }
        return BackendContext(accessToken = session.accessToken, profileId = profileId)
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
                .distinctBy { it.id }
                .sortedBy { it.releasedAtMs }

        val noScheduledSeries =
            itemsByBucket["no_scheduled"].orEmpty()
                .map { it.toCalendarSeriesItem() }
                .distinctBy { it.id }

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
        val releaseDate = airDate?.trim().takeIf { !it.isNullOrBlank() } ?: media.releaseDate?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        val releasedAtMs = parseCalendarReleaseToEpochMs(releaseDate) ?: return null
        val watchedKey = "${relatedShow.id.lowercase(Locale.US)}:$season:$episode"
        return CalendarEpisodeItem(
            id = media.id,
            seriesId = relatedShow.id,
            seriesName = relatedShow.title ?: relatedShow.subtitle ?: "Series",
            episodeTitle = media.title ?: media.subtitle,
            overview = media.summary ?: media.overview,
            season = season,
            episode = episode,
            episodeRange = null,
            episodeCount = 1,
            releaseDate = releaseDate,
            releasedAtMs = releasedAtMs,
            isReleased = releasedAtMs <= nowMs,
            isGroup = false,
            posterUrl = relatedShow.images.posterUrl,
            backdropUrl = relatedShow.images.backdropUrl,
            thumbnailUrl = media.images.stillUrl ?: media.images.backdropUrl,
            watchedKeys = setOf(watchedKey),
        )
    }

    private fun CrispyBackendClient.CalendarItem.toCalendarSeriesItem(): CalendarSeriesItem {
        return CalendarSeriesItem(
            id = relatedShow.id,
            title = relatedShow.title ?: relatedShow.subtitle ?: "Series",
            posterUrl = relatedShow.images.posterUrl,
            backdropUrl = relatedShow.images.backdropUrl,
            sourceLabel = null,
        )
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

    private data class BackendContext(
        val accessToken: String,
        val profileId: String,
    )

    companion object {
        private const val TAG = "CalendarService"
        private const val HOME_THIS_WEEK_RAW_LIMIT = 60
        private const val HOME_THIS_WEEK_RENDER_LIMIT = 20
    }
}

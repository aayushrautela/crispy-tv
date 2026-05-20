package com.crispy.tv.home

import android.util.Log
import androidx.compose.runtime.Immutable
import com.crispy.tv.backend.BackendContext
import com.crispy.tv.backend.BackendContextResolver
import com.crispy.tv.backend.CrispyBackendClient
import java.time.Instant
import java.time.LocalDate

@Immutable
data class CalendarEpisodeItem(
    val id: String,
    val titleItemId: String,
    val playbackItemId: String,
    val localKey: String,
    val highlightEpisodeId: String? = null,
    val seriesName: String,
    val episodeTitle: String?,
    val overview: String?,
    val season: Int?,
    val episode: Int?,
    val episodeRange: String?,
    val episodeCount: Int,
    val releaseDate: String?,
    val releasedAtMs: Long?,
    val isReleased: Boolean,
    val isGroup: Boolean,
    val posterUrl: String?,
    val backdropUrl: String?,
    val thumbnailUrl: String?,
    val watchedKeys: Set<String> = emptySet(),
    val absoluteEpisodeNumber: Int? = null,
    val type: String = "show",
)

@Immutable
data class CalendarSeriesItem(
    val id: String,
    val itemId: String,
    val localKey: String,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val sourceLabel: String?,
    val type: String = "show",
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
    val source: String? = null,
    val generatedAt: String? = null,
    val statusMessage: String? = null,
    val isError: Boolean = false,
)

@Immutable
data class ThisWeekResult(
    val items: List<CalendarEpisodeItem>,
    val source: String? = null,
    val kind: String? = null,
    val generatedAt: String? = null,
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
        val backendContext = backendContextResolver.resolve()
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
        val resolvedBackendContext = backendContext ?: backendContextResolver.resolve()
            ?: return CalendarSnapshot(
                sections = emptyList(),
                statusMessage = "Sign in and select a profile to load your calendar.",
                isError = true,
            )

        val response = backendClient.getCalendar(
            accessToken = resolvedBackendContext.accessToken,
            profileId = resolvedBackendContext.profileId,
        )

        val sections = response.items.toCalendarSections(nowMs)
        return CalendarSnapshot(
            sections = sections,
            source = response.source,
            generatedAt = response.generatedAt,
            statusMessage = if (sections.isEmpty()) "No upcoming episodes found right now." else null,
            isError = false,
        )
    }

    private suspend fun fetchThisWeekResult(nowMs: Long): ThisWeekResult {
        val backendContext = backendContextResolver.resolve()
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
                .map { it.toCalendarEpisodeItem(nowMs) }
                .sortedWith(compareBy(nullsLast()) { it.releasedAtMs })

        val projected = projectHomeThisWeekItems(rawItems, nowMs)
        return ThisWeekResult(
            items = projected,
            source = response.source,
            kind = null,
            generatedAt = response.generatedAt,
            statusMessage = if (projected.isEmpty()) "No episodes airing this week right now." else null,
            isError = false,
        )
    }

    private fun List<CrispyBackendClient.MediaItem>.toCalendarSections(nowMs: Long): List<CalendarSection> {
        val thisWeek = mutableListOf<CalendarEpisodeItem>()
        val upcoming = mutableListOf<CalendarEpisodeItem>()
        val recentlyReleased = mutableListOf<CalendarEpisodeItem>()
        val noScheduled = mutableListOf<CalendarSeriesItem>()

        for (media in this) {
            val date = media.releaseDate?.trim()?.takeIf { it.isNotBlank() } ?: media.airDate?.trim()?.takeIf { it.isNotBlank() }
            val releasedAtMs = parseCalendarReleaseToEpochMs(date)

            if (releasedAtMs == null) {
                val seriesItem = media.toCalendarSeriesItem()
                if (!noScheduled.any { it.itemId == seriesItem.itemId }) {
                    noScheduled.add(seriesItem)
                }
            } else if (releasedAtMs <= nowMs + WEEK_MS && releasedAtMs >= nowMs) {
                thisWeek.add(media.toCalendarEpisodeItem(nowMs))
            } else if (releasedAtMs > nowMs + WEEK_MS) {
                upcoming.add(media.toCalendarEpisodeItem(nowMs))
            } else {
                recentlyReleased.add(media.toCalendarEpisodeItem(nowMs))
            }
        }

        return buildList {
            if (thisWeek.isNotEmpty()) {
                add(CalendarSection(CalendarSectionKey.THIS_WEEK, "This Week", episodeItems = thisWeek.sortedWith(compareBy(nullsLast()) { it.releasedAtMs })))
            }
            if (upcoming.isNotEmpty()) {
                add(CalendarSection(CalendarSectionKey.UPCOMING, "Upcoming", episodeItems = upcoming.sortedWith(compareBy(nullsLast()) { it.releasedAtMs })))
            }
            if (recentlyReleased.isNotEmpty()) {
                add(CalendarSection(CalendarSectionKey.RECENTLY_RELEASED, "Recently Released", episodeItems = recentlyReleased.sortedWith(compareByDescending(nullsLast()) { it.releasedAtMs })))
            }
            if (noScheduled.isNotEmpty()) {
                add(CalendarSection(CalendarSectionKey.NO_SCHEDULED, "Series with No Scheduled Episodes", seriesItems = noScheduled))
            }
        }
    }

    private fun CrispyBackendClient.MediaItem.toCalendarEpisodeItem(nowMs: Long): CalendarEpisodeItem {
        val season = seasonNumber
        val episode = episodeNumber
        val releaseDate = releaseDate?.trim()?.takeIf { !it.isNullOrBlank() } ?: airDate?.trim()?.takeIf { !it.isNullOrBlank() }
        val releasedAtMs = parseCalendarReleaseToEpochMs(releaseDate)
        val watchedKey = if (season != null && episode != null) {
            "$itemId:$season:$episode"
        } else {
            itemId
        }
        val localKeySuffix = when {
            season != null && episode != null -> ":$season:$episode"
            !releaseDate.isNullOrBlank() -> ":${releaseDate.take(10)}"
            else -> ""
        }
        val localKey = "$itemId$localKeySuffix"
        return CalendarEpisodeItem(
            id = localKey,
            titleItemId = seriesId ?: itemId,
            playbackItemId = itemId,
            localKey = localKey,
            highlightEpisodeId = null,
            seriesName = seriesName ?: title,
            episodeTitle = episodeTitle,
            overview = overview ?: episodeTitle ?: tagline,
            season = season,
            episode = episode,
            episodeRange = null,
            episodeCount = 1,
            releaseDate = releaseDate,
            releasedAtMs = releasedAtMs,
            isReleased = releasedAtMs?.let { it <= nowMs } ?: false,
            isGroup = false,
            posterUrl = poster.medium,
            backdropUrl = backdrop.medium,
            thumbnailUrl = still.medium ?: backdrop.medium,
            watchedKeys = setOf(watchedKey),
            absoluteEpisodeNumber = absoluteEpisodeNumber,
        )
    }

    private fun CrispyBackendClient.MediaItem.toCalendarSeriesItem(): CalendarSeriesItem {
        val localKey = seriesId ?: itemId
        return CalendarSeriesItem(
            id = localKey,
            itemId = localKey,
            localKey = localKey,
            title = seriesName ?: title,
            posterUrl = poster.medium,
            backdropUrl = backdrop.medium,
            sourceLabel = null,
        )
    }

    private fun projectHomeThisWeekItems(
        items: List<CalendarEpisodeItem>,
        nowMs: Long,
    ): List<CalendarEpisodeItem> {
        val rawItems = items.take(HOME_THIS_WEEK_RAW_LIMIT)

        return rawItems
            .groupBy { item -> "${item.titleItemId}_${item.releaseDate?.take(10).orEmpty()}" }
            .values
            .map { group ->
                val sorted = group.sortedWith(compareBy(nullsLast()) { it.episode })
                val first = sorted.first()
                val last = sorted.last()
                if (sorted.size == 1) {
                    first
                } else {
                    val groupedLocalKey = "group_${first.titleItemId}_${first.releaseDate?.take(10).orEmpty()}"
                    first.copy(
                        id = groupedLocalKey,
                        localKey = groupedLocalKey,
                        highlightEpisodeId = null,
                        episodeTitle = null,
                        overview = null,
                        episodeRange = if (first.episode != null && last.episode != null) "E${first.episode}-E${last.episode}" else null,
                        episodeCount = sorted.size,
                        isGroup = true,
                        isReleased = first.releasedAtMs?.let { it <= nowMs } ?: false,
                        thumbnailUrl = sorted.firstNotNullOfOrNull { it.thumbnailUrl },
                    )
                }
            }
            .sortedWith(compareBy(nullsLast()) { it.releasedAtMs })
            .take(HOME_THIS_WEEK_RENDER_LIMIT)
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
        private const val WEEK_MS: Long = 7 * 24 * 60 * 60 * 1000L
    }

    private data class CachedCalendarSnapshot(
        val profileId: String,
        val snapshot: CalendarSnapshot,
    )
}

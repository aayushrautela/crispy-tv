package com.crispy.tv.home

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.player.CanonicalContinueWatchingItem
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

@Immutable
data class HeroState(
    val items: List<HomeHeroItem> = emptyList(),
    val selectedId: String? = null,
    val isLoading: Boolean = true,
)

@Immutable
data class HomeCatalogSectionUi(
    val section: CatalogSectionRef,
    val items: List<com.crispy.tv.catalog.CatalogItem> = emptyList(),
    val isLoading: Boolean = true,
)

data class HomePrimarySnapshot(
    val hero: HeroState = HeroState(),
    val headerPills: List<CatalogSectionRef> = emptyList(),
    val catalogSections: List<HomeCatalogSectionUi> = emptyList(),
)

const val CONTINUE_WATCHING_SECTION_KEY = "continueWatching"
const val UP_NEXT_SECTION_KEY = "upNext"
const val THIS_WEEK_SECTION_KEY = "thisWeek"

@Immutable
data class HomeUiState(
    val headerPills: List<CatalogSectionRef> = emptyList(),
    val heroState: HeroState = HeroState(),
    val layoutState: HomeLayoutState = defaultHomeLayoutState(),
    val wideRailSections: Map<String, HomeWideRailSectionUi> = linkedMapOf(
        CONTINUE_WATCHING_SECTION_KEY to defaultWideRailSection(
            key = CONTINUE_WATCHING_SECTION_KEY,
            title = "Continue Watching",
            kind = HomeWideRailSectionKind.CONTINUE_WATCHING,
        ),
    ),
    val catalogSections: Map<String, HomeCatalogSectionUi> = emptyMap(),
)

fun defaultHomeLayoutState(): HomeLayoutState {
    return HomeLayoutState(
        blocks = listOf(
            HomeWideRailLayoutUi(
                key = CONTINUE_WATCHING_SECTION_KEY,
                kind = HomeWideRailSectionKind.CONTINUE_WATCHING,
            ),
        ),
    )
}

fun defaultWideRailSection(
    key: String,
    title: String,
    kind: HomeWideRailSectionKind,
    items: List<HomeWideRailItemUi>? = null,
): HomeWideRailSectionUi {
    return HomeWideRailSectionUi(
        key = key,
        title = title,
        kind = kind,
        state = items?.let { RailLoadState.Ready(it) } ?: RailLoadState.Loading,
    )
}

fun CanonicalContinueWatchingItem.toWideRailItem(nowMs: Long): HomeWideRailItemUi {
    return HomeWideRailItemUi(
        key = "${type}:${localKey}",
        title = title,
        subtitle = buildHomeWatchActivitySubtitle(),
        imageUrl = stillUrl ?: artworkUrl,
        logoUrl = logoUrl,
        progressFraction = progressPercent?.takeIf { it > 0.0 }?.let { (it / 100.0).coerceIn(0.0, 1.0).toFloat() },
        kind = HomeWideRailItemKind.WATCH_ACTIVITY,
        continueWatchingItem = this,
        detailsItemId = titleItemId,
    )
}

fun CalendarEpisodeItem.toWideRailItem(): HomeWideRailItemUi {
    return HomeWideRailItemUi(
        key = "${type}:${localKey}",
        title = seriesName,
        subtitle = buildCalendarSecondaryText(),
        imageUrl = thumbnailUrl ?: artworkUrl,
        badgeLabel = buildCalendarBadgeLabel(),
        kind = HomeWideRailItemKind.CALENDAR_EPISODE,
        calendarEpisodeItem = this,
        detailsItemId = titleItemId,
    )
}

private fun CalendarEpisodeItem.buildCalendarBadgeLabel(): String? {
    if (isReleased) return "Released"
    val normalizedReleaseDate = releaseDate ?: return null
    return try {
        val date = LocalDate.parse(normalizedReleaseDate.take(10))
        "${date.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${date.dayOfMonth}"
    } catch (_: Exception) {
        null
    }
}

private fun CalendarEpisodeItem.buildCalendarSecondaryText(): String {
    val supportingText =
        when {
            isGroup -> "${episodeCount} new episodes"
            !episodeTitle.isNullOrBlank() -> episodeTitle
            !overview.isNullOrBlank() -> overview
            else -> null
        }?.trim()

    val episodeLabel =
        when {
            episodeRange != null && season != null -> "S${season} ${episodeRange}"
            season != null && episode != null -> "S${season} E${episode}"
            episodeRange != null -> episodeRange
            episode != null -> "Episode ${episode}"
            releaseDate != null -> releaseDate.take(10)
            else -> "Upcoming episode"
        }
    return if (supportingText.isNullOrBlank()) {
        episodeLabel
    } else {
        "$episodeLabel - $supportingText"
    }
}

class ContinueWatchingSuppressionStore(context: Context) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): MutableMap<String, Long> {
        val raw = preferences.getString(KEY_ITEM_SUPPRESSIONS, null) ?: return mutableMapOf()
        val payload = runCatching { JSONObject(raw) }.getOrNull() ?: return mutableMapOf()
        val map = mutableMapOf<String, Long>()
        payload.keys().forEach { key ->
            val timestamp = payload.optLong(key)
            if (timestamp > 0L) {
                map[key] = timestamp
            }
        }
        return map
    }

    fun write(value: Map<String, Long>) {
        if (value.isEmpty()) {
            preferences.edit().remove(KEY_ITEM_SUPPRESSIONS).apply()
            return
        }

        val payload = JSONObject()
        value.forEach { (key, timestamp) ->
            payload.put(key, timestamp)
        }
        preferences.edit().putString(KEY_ITEM_SUPPRESSIONS, payload.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "home_continue_watching"
        private const val KEY_ITEM_SUPPRESSIONS = "suppressed_items"
    }
}

fun CanonicalContinueWatchingItem.sectionKey(): String {
    return CONTINUE_WATCHING_SECTION_KEY
}

fun continueWatchingContentKey(entry: CanonicalContinueWatchingItem): String {
    return entry.titleItemId.trim().ifBlank { entry.id.trim().lowercase(Locale.US) }
}

private fun CanonicalContinueWatchingItem.buildHomeWatchActivitySubtitle(): String {
    val isShow = type.equals("show", ignoreCase = true) || type.equals("anime", ignoreCase = true)
    return if (isShow) {
        val seasonEpisode = if (season != null && episode != null) {
            String.format(Locale.US, "S%02dE%02d", season, episode)
        } else {
            null
        }
        val episodeName = episodeTitle?.takeIf { it.isNotBlank() }
        listOfNotNull(seasonEpisode, episodeName).joinToString(separator = ": ")
    } else {
        genre.orEmpty()
    }
}

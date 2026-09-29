package com.crispy.tv.home

import androidx.compose.runtime.Immutable
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.player.CanonicalContinueWatchingItem
import com.crispy.tv.domain.watch.iso8601MonthLabel

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
    // The label and the day are read from the same validated parse. Splitting them
    // would mean validating the month with one parser and the day with another, and
    // "Feb 31" would then render a badge instead of nothing.
    val monthLabel = iso8601MonthLabel(normalizedReleaseDate) ?: return null
    val dayOfMonth = normalizedReleaseDate.substring(8, 10).toInt()
    return "$monthLabel $dayOfMonth"
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


fun CanonicalContinueWatchingItem.sectionKey(): String {
    return CONTINUE_WATCHING_SECTION_KEY
}

fun continueWatchingContentKey(entry: CanonicalContinueWatchingItem): String {
    // Was `lowercase(Locale.US)`, which pinned this file to the JVM. Kotlin's
    // `lowercase()` is locale-invariant by definition, so for the ASCII ids an add-on
    // publishes the two are identical, and where they could differ the invariant form
    // is the predictable one. `CatalogModels.key` already made this exact change for
    // the same reason.
    return entry.titleItemId.trim().ifBlank { entry.id.trim().lowercase() }
}

private fun CanonicalContinueWatchingItem.buildHomeWatchActivitySubtitle(): String {
    val isShow = type.equals("show", ignoreCase = true) || type.equals("anime", ignoreCase = true)
    return if (isShow) {
        val seasonEpisode = if (season != null && episode != null) {
            // `String.format(Locale.US, "S%02dE%02d", ...)` is JVM-only. `%02d` on a
            // non-negative Int is zero-padding to width 2 and nothing else, so
            // `padStart` is the whole of it; a season or episode past 99 is wider
            // than 2 either way and is not truncated by either form.
            "S" + season.toString().padStart(2, '0') + "E" + episode.toString().padStart(2, '0')
        } else {
            null
        }
        val episodeName = episodeTitle?.takeIf { it.isNotBlank() }
        listOfNotNull(seasonEpisode, episodeName).joinToString(separator = ": ")
    } else {
        genre.orEmpty()
    }
}

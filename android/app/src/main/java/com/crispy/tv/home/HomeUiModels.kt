package com.crispy.tv.home

import androidx.compose.runtime.Immutable
@Immutable
data class HomeLayoutState(
    val blocks: List<HomeContentSectionUi> = emptyList(),
)

@Immutable
sealed interface HomeContentSectionUi {
    val key: String
}

@Immutable
data class HomeWideRailLayoutUi(
    override val key: String,
    val kind: HomeWideRailSectionKind,
) : HomeContentSectionUi

enum class HomeWideRailSectionKind {
    CONTINUE_WATCHING,
    UP_NEXT,
    THIS_WEEK,
}

enum class HomeWideRailItemKind {
    WATCH_ACTIVITY,
    CALENDAR_EPISODE,
}

@Immutable
data class HomeWideRailItemUi(
    val key: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
    val badgeLabel: String? = null,
    val progressFraction: Float? = null,
    val kind: HomeWideRailItemKind,
    val continueWatchingItem: ContinueWatchingItem? = null,
    val calendarEpisodeItem: CalendarEpisodeItem? = null,
)

@Immutable
data class HomeWideRailSectionUi(
    val key: String,
    val title: String,
    val kind: HomeWideRailSectionKind,
    val items: List<HomeWideRailItemUi> = emptyList(),
    val isLoading: Boolean = false,
    val statusMessage: String = "",
)

@Immutable
data class HomeCatalogRowSectionUi(
    override val key: String,
    val sectionKey: String,
) : HomeContentSectionUi

@Immutable
data class HomeCollectionShelfSectionUi(
    override val key: String,
    val sectionKeys: List<String>,
) : HomeContentSectionUi

@Immutable
data class HomeStatusSectionUi(
    override val key: String,
    val statusMessage: String,
) : HomeContentSectionUi

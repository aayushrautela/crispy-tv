package com.crispy.tv.home

import androidx.compose.runtime.Immutable
import com.crispy.tv.player.CanonicalContinueWatchingItem

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
    val logoUrl: String? = null,
    val badgeLabel: String? = null,
    val progressFraction: Float? = null,
    val kind: HomeWideRailItemKind,
    val continueWatchingItem: CanonicalContinueWatchingItem? = null,
    val calendarEpisodeItem: CalendarEpisodeItem? = null,
    val detailsItemId: String? = null,
)

@Immutable
sealed interface RailLoadState {
    data object Loading : RailLoadState
    data class Ready(val items: List<HomeWideRailItemUi>) : RailLoadState
}

@Immutable
data class HomeWideRailSectionUi(
    val key: String,
    val title: String,
    val kind: HomeWideRailSectionKind,
    val state: RailLoadState,
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

package com.crispy.tv.home

import androidx.compose.runtime.Immutable
import com.crispy.tv.catalog.CatalogSectionRef

@Immutable
data class HomeUiState(
    val isRefreshing: Boolean = false,
    val headerPills: List<CatalogSectionRef> = emptyList(),
    val hero: HeroState = HeroState(),
    val sections: List<HomeContentSectionUi> = emptyList(),
)

@Immutable
sealed interface HomeContentSectionUi {
    val key: String
}

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
    override val key: String,
    val title: String,
    val kind: HomeWideRailSectionKind,
    val items: List<HomeWideRailItemUi> = emptyList(),
    val isLoading: Boolean = false,
    val statusMessage: String = "",
) : HomeContentSectionUi

@Immutable
data class HomeCatalogRowSectionUi(
    override val key: String,
    val sectionUi: HomeCatalogSectionUi,
) : HomeContentSectionUi

@Immutable
data class HomeCollectionShelfSectionUi(
    override val key: String,
    val sectionUis: List<HomeCatalogSectionUi>,
) : HomeContentSectionUi

@Immutable
data class HomeStatusSectionUi(
    override val key: String,
    val statusMessage: String,
) : HomeContentSectionUi

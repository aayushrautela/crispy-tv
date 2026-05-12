package com.crispy.tv.home

import com.crispy.tv.player.CanonicalContinueWatchingItem
import com.crispy.tv.player.CanonicalContinueWatchingResult
import com.crispy.tv.player.WatchHistoryService
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

internal data class HomeRefreshSnapshot(
    val primary: HomePrimarySnapshot,
    val watchActivity: HomeWatchActivitySnapshot,
    val thisWeek: HomeWideRailSectionUi,
)

internal class HomeRefreshCoordinator(
    private val recommendationCatalogService: RecommendationCatalogService,
    private val homeWatchActivityService: HomeWatchActivityService,
    private val watchHistoryService: WatchHistoryService,
    private val calendarService: CalendarService,
    private val suppressionStore: ContinueWatchingSuppressionStore,
) {
    private val continueWatchingLimit = 30

    suspend fun loadCachedPrimarySnapshot(): HomePrimarySnapshot? {
        val primaryFeedResult = recommendationCatalogService.loadCachedPrimaryHomeFeed(heroLimit = 10)
            ?: return null

        val heroItems = primaryFeedResult.heroResult.items
        val allSections = primaryFeedResult.sections
        val headerPills = allSections
            .asSequence()
            .filter { it.presentation == com.crispy.tv.domain.home.HomeCatalogPresentation.PILL }
            .filter { it.displayTitle.trim().isNotEmpty() }
            .distinctBy { it.key }
            .toList()
        val catalogSections = allSections
            .asSequence()
            .filter { it.presentation != com.crispy.tv.domain.home.HomeCatalogPresentation.PILL }
            .map { section ->
                HomeCatalogSectionUi(
                    section = section,
                    items = section.previewItems,
                    isLoading = false,
                )
            }
            .toList()
        val selectedId = heroItems.firstOrNull()?.id

        return HomePrimarySnapshot(
            hero = HeroState(
                items = heroItems,
                selectedId = selectedId,
                isLoading = false,
                statusMessage = primaryFeedResult.heroResult.statusMessage,
            ),
            headerPills = headerPills,
            catalogSections = catalogSections,
            catalogStatusMessage = primaryFeedResult.sectionsStatusMessage,
        )
    }

    suspend fun loadPrimarySnapshot(): HomePrimarySnapshot {
        val primaryFeedResult = recommendationCatalogService.loadPrimaryHomeFeed(heroLimit = 10)

        val heroItems = primaryFeedResult.heroResult.items
        val allSections = primaryFeedResult.sections
        val headerPills = allSections
            .asSequence()
            .filter { it.presentation == com.crispy.tv.domain.home.HomeCatalogPresentation.PILL }
            .filter { it.displayTitle.trim().isNotEmpty() }
            .distinctBy { it.key }
            .toList()
        val catalogSections = allSections
            .asSequence()
            .filter { it.presentation != com.crispy.tv.domain.home.HomeCatalogPresentation.PILL }
            .map { section ->
                HomeCatalogSectionUi(
                    section = section,
                    items = section.previewItems,
                    isLoading = false,
                )
            }
            .toList()
        val selectedId = heroItems.firstOrNull()?.id

        return HomePrimarySnapshot(
            hero = HeroState(
                items = heroItems,
                selectedId = selectedId,
                isLoading = false,
                statusMessage = primaryFeedResult.heroResult.statusMessage,
            ),
            headerPills = headerPills,
            catalogSections = catalogSections,
            catalogStatusMessage = primaryFeedResult.sectionsStatusMessage,
        )
    }

    suspend fun loadWatchActivitySnapshot(): HomeWatchActivitySnapshot {
        val suppressionMap = suppressionStore.read()
        val canonicalResult = watchHistoryService.getCanonicalContinueWatching(limit = continueWatchingLimit)
        val filteredCanonicalResult = canonicalResult.copy(
            entries = applyProviderSuppressionFilter(canonicalResult.entries, suppressionMap),
        )

        val continueWatchingResult = homeWatchActivityService.loadWatchActivity(
            canonicalResult = filteredCanonicalResult,
            limit = continueWatchingLimit,
        )

        val nowMs = System.currentTimeMillis()
        val railItems = continueWatchingResult.entries
        val continueWatchingItems = railItems

        return HomeWatchActivitySnapshot(
            continueWatching = defaultWideRailSection(
                key = CONTINUE_WATCHING_SECTION_KEY,
                title = "Continue Watching",
                kind = HomeWideRailSectionKind.CONTINUE_WATCHING,
            ).copy(
                items = continueWatchingItems.map { item -> item.toWideRailItem(nowMs) },
                isLoading = false,
                statusMessage = continueWatchingResult.statusMessage.takeIf {
                    continueWatchingItems.isNotEmpty() || continueWatchingResult.isError
                }.orEmpty(),
            ),
            upNext = defaultWideRailSection(
                key = UP_NEXT_SECTION_KEY,
                title = "Up Next",
                kind = HomeWideRailSectionKind.UP_NEXT,
            ).copy(isLoading = false),
        )
    }

    suspend fun loadThisWeekSection(): HomeWideRailSectionUi {
        val thisWeekResult = calendarService.loadThisWeek(System.currentTimeMillis())

        return defaultWideRailSection(
            key = THIS_WEEK_SECTION_KEY,
            title = "This Week",
            kind = HomeWideRailSectionKind.THIS_WEEK,
        ).copy(
            items = thisWeekResult.items.map { item -> item.toWideRailItem() },
            isLoading = false,
            statusMessage = thisWeekResult.statusMessage.takeIf {
                thisWeekResult.items.isNotEmpty() || thisWeekResult.isError
            }.orEmpty(),
        )
    }

    suspend fun loadAll(): HomeRefreshSnapshot = coroutineScope {
        val primaryDeferred = async { loadPrimarySnapshot() }
        val watchActivityDeferred = async { loadWatchActivitySnapshot() }
        val thisWeekDeferred = async { loadThisWeekSection() }

        HomeRefreshSnapshot(
            primary = primaryDeferred.await(),
            watchActivity = watchActivityDeferred.await(),
            thisWeek = thisWeekDeferred.await(),
        )
    }

    private fun applyProviderSuppressionFilter(
        entries: List<CanonicalContinueWatchingItem>,
        suppressionMap: MutableMap<String, Long>,
    ): List<CanonicalContinueWatchingItem> {
        if (entries.isEmpty()) return emptyList()

        var updated = false
        val filtered = mutableListOf<CanonicalContinueWatchingItem>()
        entries.forEach { entry ->
            val key = continueWatchingContentKey(entry)
            val suppressedAt = suppressionMap[key]
            if (suppressedAt == null) {
                filtered += entry
                return@forEach
            }

            if (entry.lastUpdatedEpochMs > suppressedAt) {
                suppressionMap.remove(key)
                updated = true
                filtered += entry
            }
        }

        if (updated) {
            suppressionStore.write(suppressionMap)
        }

        return filtered
    }
}

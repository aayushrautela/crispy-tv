package com.crispy.tv.home

import com.crispy.tv.player.CanonicalContinueWatchingItem
import com.crispy.tv.player.WatchHistoryService

internal class HomeRefreshCoordinator(
    private val homeCatalogService: HomeCatalogService,
    private val homeWatchActivityService: HomeWatchActivityService,
    private val watchHistoryService: WatchHistoryService,
    private val calendarService: CalendarService,
    private val upNextService: UpNextService,
    private val suppressionStore: ContinueWatchingSuppressionStore,
) {
    private val continueWatchingLimit = 30

    suspend fun loadCachedPrimarySnapshot(): HomePrimarySnapshot? {
        val primaryFeedResult = homeCatalogService.loadCachedPrimaryHomeFeed()
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
            ),
            headerPills = headerPills,
            catalogSections = catalogSections,
        )
    }

    suspend fun loadPrimarySnapshot(): HomePrimarySnapshot {
        val primaryFeedResult = homeCatalogService.loadPrimaryHomeFeed()

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
            ),
            headerPills = headerPills,
            catalogSections = catalogSections,
        )
    }

    suspend fun loadContinueWatching(): HomeWideRailSectionUi {
        val suppressionMap = suppressionStore.read()
        val canonicalResult = watchHistoryService.getCanonicalContinueWatching(limit = continueWatchingLimit)
        val filtered = canonicalResult.copy(
            entries = applyProviderSuppressionFilter(canonicalResult.entries, suppressionMap),
        )

        val result = homeWatchActivityService.loadWatchActivity(
            canonicalResult = filtered,
            limit = continueWatchingLimit,
        )
        if (result.isError) {
            throw IllegalStateException(result.statusMessage.ifBlank { "Unable to load continue watching." })
        }

        val nowMs = System.currentTimeMillis()
        return defaultWideRailSection(
            key = CONTINUE_WATCHING_SECTION_KEY,
            title = "Continue Watching",
            kind = HomeWideRailSectionKind.CONTINUE_WATCHING,
        ).copy(
            items = result.entries.map { item -> item.toWideRailItem(nowMs) },
            isLoading = false,
        )
    }

    suspend fun loadUpNext(): HomeWideRailSectionUi = upNextService.loadUpNext(System.currentTimeMillis())

    suspend fun loadThisWeekSection(): HomeWideRailSectionUi {
        val thisWeekResult = calendarService.loadThisWeek(System.currentTimeMillis())
        if (thisWeekResult.isError) {
            throw IllegalStateException(thisWeekResult.statusMessage ?: "Unable to load this week.")
        }

        return defaultWideRailSection(
            key = THIS_WEEK_SECTION_KEY,
            title = "This Week",
            kind = HomeWideRailSectionKind.THIS_WEEK,
        ).copy(
            items = thisWeekResult.items.map { item -> item.toWideRailItem() },
            isLoading = false,
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

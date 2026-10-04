package com.crispy.tv.home

import com.crispy.tv.domain.home.HomeRandomCandidate
import com.crispy.tv.platform.TimeSource
import com.crispy.tv.player.CanonicalContinueWatchingItem
import com.crispy.tv.player.WatchHistoryService

class HomeRefreshCoordinator(
    private val homeCatalogService: HomeCatalogService,
    private val homeWatchActivityService: HomeWatchActivityService,
    private val watchHistoryService: WatchHistoryService,
    private val calendarService: CalendarService,
    private val upNextService: UpNextService,
    private val suppressionStore: ContinueWatchingSuppressionStore,
    private val timeSource: TimeSource,
) {
    private val continueWatchingLimit = 30

    suspend fun cachedHomeExpiresAtMs(): Long? {
        return homeCatalogService.cachedHomeExpiresAtMs()
    }

    /**
     * The random-pick wheel's candidates, straight from the coordinator's service.
     *
     * A delegating hop rather than a widened constructor, because
     * `homeCatalogService` is private here and handing `HomeViewModel` a second
     * service reference would be a wiring decision made for one caller's benefit.
     */
    suspend fun loadRandomCandidates(): List<HomeRandomCandidate> {
        return homeCatalogService.loadRandomCandidates()
    }

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

    suspend fun loadContinueWatching(): HomeWideRailSectionUi? {
        val suppressionMap = suppressionStore.read()
        // Read once, then reused. The two calls below used to read the system clock
        // independently, so one refresh could see three different "now" values a
        // millisecond apart — and a continue-watching entry could be filtered against
        // one instant and then rendered against another. Pinning them to a single
        // reading is also what makes this testable, which reading the clock inline
        // never was.
        val nowMs = timeSource.nowMs()
        val canonicalResult = watchHistoryService.getCanonicalContinueWatching(
            limit = continueWatchingLimit,
            nowMs = nowMs,
        )
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

        val items = result.entries.map { item -> item.toWideRailItem(nowMs) }
        if (items.isEmpty()) return null

        return defaultWideRailSection(
            key = CONTINUE_WATCHING_SECTION_KEY,
            title = "Continue Watching",
            kind = HomeWideRailSectionKind.CONTINUE_WATCHING,
            items = items,
        )
    }

    suspend fun loadUpNext(): HomeWideRailSectionUi? = upNextService.loadUpNext(timeSource.nowMs())

    suspend fun loadThisWeekSection(): HomeWideRailSectionUi? {
        val thisWeekResult = calendarService.loadThisWeek(timeSource.nowMs())
        if (thisWeekResult.isError) {
            throw IllegalStateException(thisWeekResult.statusMessage ?: "Unable to load this week.")
        }

        val items = thisWeekResult.items.map { item -> item.toWideRailItem() }
        if (items.isEmpty()) return null

        return defaultWideRailSection(
            key = THIS_WEEK_SECTION_KEY,
            title = "This Week",
            kind = HomeWideRailSectionKind.THIS_WEEK,
            items = items,
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

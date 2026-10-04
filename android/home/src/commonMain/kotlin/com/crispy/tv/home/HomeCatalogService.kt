package com.crispy.tv.home

import androidx.compose.runtime.Immutable
import com.crispy.tv.catalog.CatalogPageResult
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.domain.home.HomeRandomCandidate

@Immutable
data class HomeHeroLoadResult(
    val items: List<HomeHeroItem> = emptyList(),
    val statusMessage: String = "",
)

@Immutable
data class HomePrimaryFeedLoadResult(
    val heroResult: HomeHeroLoadResult = HomeHeroLoadResult(),
    val sections: List<CatalogSectionRef> = emptyList(),
    val sectionsStatusMessage: String = "",
)

/**
 * The home catalog, as its callers see it.
 *
 * The implementation stays in `androidMain` permanently, and not because
 * anything about fetching is Android-specific: it maps the wire through
 * `org.json`, which is a class of the Android platform supplied by
 * `android.jar` rather than a dependency of this project, so a KMP source set
 * does not have it at all. The interface travels; the mapping does not.
 *
 * Named after the class it replaces, so the existing consumers --
 * `HomeRefreshCoordinator`, `CatalogPagingSource`, `CatalogViewModel` and
 * `TvHomeViewModel` -- needed no import edit when the seam went in. Only the
 * construction sites name `CachingHomeCatalogService`.
 */
interface HomeCatalogService {
    suspend fun loadPrimaryHomeFeed(sectionLimit: Int = Int.MAX_VALUE): HomePrimaryFeedLoadResult

    suspend fun loadCachedPrimaryHomeFeed(sectionLimit: Int = Int.MAX_VALUE): HomePrimaryFeedLoadResult?

    suspend fun fetchCatalogPage(
        section: CatalogSectionRef,
        page: Int,
        pageSize: Int,
    ): CatalogPageResult

    suspend fun cachedHomeExpiresAtMs(): Long?

    /**
     * Every home item, as the random-pick wheel's candidates.
     *
     * Deliberately not [fetchCatalogPage] per section: the wheel is not paging a
     * list, it wants every item the snapshot holds in one read, and a per-section
     * call would fan out into one fetch per row. Hero and pill lists are included
     * because they are home data too -- the caller deduplicates by id.
     *
     * The interface cannot promise this is free. The implementation deduplicates
     * *concurrent* snapshot loads only, so the first call in a session reaches the
     * backend and a later one does too unless the caller memoises the result.
     */
    suspend fun loadRandomCandidates(): List<HomeRandomCandidate>
}
}

package com.crispy.tv.home

import androidx.compose.runtime.Immutable
import com.crispy.tv.accounts.ActiveProfileStore
import com.crispy.tv.accounts.SupabaseAccountClient
import com.crispy.tv.catalog.CatalogPageResult
import com.crispy.tv.catalog.CatalogSectionRef
import com.crispy.tv.catalog.DiscoverCatalogRef
import com.crispy.tv.network.CrispyHttpClient

@Immutable
data class HomeHeroItem(
    val id: String,
    val title: String,
    val description: String,
    val rating: String?,
    val year: String? = null,
    val genres: List<String> = emptyList(),
    val backdropUrl: String,
    val addonId: String,
    val type: String,
)

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

@Immutable
data class MediaDetails(
    val id: String,
    val imdbId: String?,
    val mediaType: String,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val logoUrl: String?,
    val description: String?,
    val genres: List<String> = emptyList(),
    val year: String?,
    val runtime: String?,
    val certification: String?,
    val rating: String?,
    val cast: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val creators: List<String> = emptyList(),
    val videos: List<MediaVideo> = emptyList(),
    val addonId: String?,
)

@Immutable
data class MediaVideo(
    val id: String,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val released: String?,
    val overview: String?,
    val thumbnailUrl: String?,
)

class HomeCatalogService internal constructor(
    supabaseAccountClient: SupabaseAccountClient,
    activeProfileStore: ActiveProfileStore,
    httpClient: CrispyHttpClient,
    supabaseUrl: String,
    supabasePublishableKey: String,
    diskCacheStore: HomeCatalogDiskCacheStore,
) {
    private val disabledMessage =
        "Home and discover are temporarily disabled until Android migrates to backend /v1/home."

    suspend fun loadPrimaryHomeFeed(
        heroLimit: Int = 10,
        sectionLimit: Int = Int.MAX_VALUE,
    ): HomePrimaryFeedLoadResult {
        return HomePrimaryFeedLoadResult(
            heroResult = HomeHeroLoadResult(statusMessage = disabledMessage),
            sections = emptyList(),
            sectionsStatusMessage = disabledMessage,
        )
    }

    suspend fun listDiscoverCatalogs(
        mediaType: String? = null,
        limit: Int = Int.MAX_VALUE,
    ): Pair<List<DiscoverCatalogRef>, String> {
        return emptyList<DiscoverCatalogRef>() to disabledMessage
    }

    suspend fun fetchCatalogPage(
        section: CatalogSectionRef,
        page: Int,
        pageSize: Int,
    ): CatalogPageResult {
        return CatalogPageResult(
            items = emptyList(),
            statusMessage = disabledMessage,
            attemptedUrls = listOf("backend:/v1/home (not implemented in Android yet)"),
        )
    }
}

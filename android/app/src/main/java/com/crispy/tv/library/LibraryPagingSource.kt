package com.crispy.tv.library

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.crispy.tv.backend.BackendContextResolver
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.backend.CrispyBackendClient.ClientMediaCard
import com.crispy.tv.backend.CrispyBackendClient.ClientMediaCardQueryResult
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.images.toUiResponsiveImageSet
import com.crispy.tv.addons.util.formatRating
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LibraryPagingSource(
    private val backend: CrispyBackendClient,
    private val backendContextResolver: BackendContextResolver,
    private val sectionId: String,
) : PagingSource<String, CatalogItem>() {
    override fun getRefreshKey(state: PagingState<String, CatalogItem>): String? = null

    override suspend fun load(params: LoadParams<String>): LoadResult<String, CatalogItem> {
        return runCatching {
            val backendContext =
                if (backend.isConfigured()) {
                    backendContextResolver.resolve()
                } else {
                    null
                }
                    ?: return LoadResult.Error(IllegalStateException("Sign in and select a profile to load your library."))

            val page =
                withContext(Dispatchers.IO) {
                    loadLibrarySectionPage(
                        backend = backend,
                        accessToken = backendContext.accessToken,
                        profileId = backendContext.profileId,
                        sectionId = sectionId,
                        limit = params.loadSize.coerceAtLeast(1),
                        cursor = params.key,
                    )
                }

            LoadResult.Page(
                data = page.items,
                prevKey = null,
                nextKey = page.nextCursor?.takeIf { page.hasMore && it.isNotBlank() },
            )
        }.getOrElse { error ->
            LoadResult.Error(error)
        }
    }
}

private suspend fun loadLibrarySectionPage(
    backend: CrispyBackendClient,
    accessToken: String,
    profileId: String,
    sectionId: String,
    limit: Int,
    cursor: String?,
): LibrarySectionPageUi {
    return when (sectionId) {
        LIBRARY_SECTION_HISTORY -> {
            backend.listWatchHistory(
                accessToken = accessToken,
                profileId = profileId,
                limit = limit,
                cursor = cursor,
            ).toHistorySectionPageUi()
        }

        LIBRARY_SECTION_WATCHLIST -> {
            backend.listWatchlist(
                accessToken = accessToken,
                profileId = profileId,
                limit = limit,
                cursor = cursor,
            ).toWatchlistSectionPageUi()
        }

        LIBRARY_SECTION_RATINGS -> {
            backend.listRatings(
                accessToken = accessToken,
                profileId = profileId,
                limit = limit,
                cursor = cursor,
            ).toRatingsSectionPageUi()
        }

        else -> LibrarySectionPageUi()
    }
}

private fun ClientMediaCard.toCatalogItem(
    watchedAt: String?,
    ratingValue: Int?,
    lastActivityAt: String?,
): CatalogItem {
    val isEpisode = mediaType.equals("episode", ignoreCase = true)
    val showItemId = if (isEpisode) parent?.seriesItemId else null
    val showTitle = if (isEpisode) parent?.seriesTitle else null
    val seriesArtwork = if (isEpisode) parent?.images?.artwork?.takeIf { !it.isEmpty } else null
    val cardArtwork = seriesArtwork ?: images.artwork
    val logoUrl = images.logo.medium ?: images.logo.large ?: images.logo.small
    return CatalogItem(
        id = itemId,
        itemId = showItemId ?: itemId,
        title = showTitle ?: title,
        artworkUrl = cardArtwork.medium,
        logoUrl = logoUrl,
        artwork = cardArtwork.toUiResponsiveImageSet(),
        logo = images.logo.toUiResponsiveImageSet(),
        addonId = "backend",
        type = mediaType.toCatalogType(),
        rating = formatRating(rating),
        year = year?.toString(),
        genre = genres.firstOrNull(),
        maturityRating = maturityRating,
        watchedAt = watchedAt,
        ratingValue = ratingValue,
        lastActivityAt = lastActivityAt,
        episodeCount = if (isEpisode) 1 else null,
    )
}

private fun ClientMediaCard.libraryCatalogItemFromProgress(): CatalogItem {
    val progress = progress
    val lastActivityAt = progress?.lastPlayedAt
    val watchedAt = lastActivityAt?.takeIf { progress.played == true }
    val ratingValue = progress?.userRating?.toInt()?.takeIf { it in 1..10 }
    return toCatalogItem(
        watchedAt = watchedAt,
        ratingValue = ratingValue,
        lastActivityAt = lastActivityAt,
    )
}

private fun ClientMediaCardQueryResult.toHistorySectionPageUi(): LibrarySectionPageUi {
    return LibrarySectionPageUi(
        items = items.map { card -> card.libraryCatalogItemFromProgress() },
        nextCursor = nextCursor,
        hasMore = hasMore,
    )
}

private fun ClientMediaCardQueryResult.toWatchlistSectionPageUi(): LibrarySectionPageUi {
    return LibrarySectionPageUi(
        items = items.map { card -> card.libraryCatalogItemFromProgress() },
        nextCursor = nextCursor,
        hasMore = hasMore,
    )
}

private fun ClientMediaCardQueryResult.toRatingsSectionPageUi(): LibrarySectionPageUi {
    return LibrarySectionPageUi(
        items = items.map { card -> card.libraryCatalogItemFromProgress() },
        nextCursor = nextCursor,
        hasMore = hasMore,
    )
}

private fun String.toCatalogType(): String {
    return when (trim().lowercase()) {
        "anime" -> "anime"
        "episode", "show", "tv", "series" -> "show"
        else -> "movie"
    }
}

internal data class LibrarySectionPageUi(
    val items: List<CatalogItem> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

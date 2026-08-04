package com.crispy.tv.library

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.crispy.tv.backend.BackendContextResolver
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.backend.CrispyBackendClient.ClientMediaCard
import com.crispy.tv.backend.CrispyBackendClient.ClientMediaCardQueryResult
import com.crispy.tv.images.toUiResponsiveImageSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LibraryPagingSource(
    private val backend: CrispyBackendClient,
    private val backendContextResolver: BackendContextResolver,
    private val sectionId: String,
) : PagingSource<String, LibrarySectionItemUi>() {
    override fun getRefreshKey(state: PagingState<String, LibrarySectionItemUi>): String? = null

    override suspend fun load(params: LoadParams<String>): LoadResult<String, LibrarySectionItemUi> {
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

private fun ClientMediaCard.toLibrarySectionItemUi(
    watchedAt: String?,
    ratingValue: Int?,
    lastActivityAt: String?,
): LibrarySectionItemUi {
    return LibrarySectionItemUi(
        id = itemId,
        itemId = itemId,
        itemType = mediaType,
        title = title,
        posterUrl = images.poster.medium,
        backdropUrl = images.backdrop.medium,
        logoUrl = images.logo.medium,
        poster = images.poster.toUiResponsiveImageSet(),
        backdrop = images.backdrop.toUiResponsiveImageSet(),
        logo = images.logo.toUiResponsiveImageSet(),
        rating = rating,
        year = year,
        genre = genres.firstOrNull(),
        maturityRating = maturityRating,
        addedAt = null,
        watchedAt = watchedAt,
        ratedAt = null,
        ratingValue = ratingValue,
        lastActivityAt = lastActivityAt,
        origins = emptyList(),
    )
}

private fun ClientMediaCard.librarySectionItemFromProgress(): LibrarySectionItemUi {
    val progress = progress
    val lastActivityAt = progress?.lastPlayedAt
    val watchedAt = lastActivityAt?.takeIf { progress?.played == true }
    val ratingValue = progress?.userRating?.toInt()?.takeIf { it in 1..10 }
    return toLibrarySectionItemUi(
        watchedAt = watchedAt,
        ratingValue = ratingValue,
        lastActivityAt = lastActivityAt,
    )
}

private fun ClientMediaCardQueryResult.toHistorySectionPageUi(): LibrarySectionPageUi {
    return LibrarySectionPageUi(
        items = items.map { card -> card.librarySectionItemFromProgress() },
        nextCursor = nextCursor,
        hasMore = hasMore,
    )
}

private fun ClientMediaCardQueryResult.toWatchlistSectionPageUi(): LibrarySectionPageUi {
    return LibrarySectionPageUi(
        items = items.map { card -> card.librarySectionItemFromProgress() },
        nextCursor = nextCursor,
        hasMore = hasMore,
    )
}

private fun ClientMediaCardQueryResult.toRatingsSectionPageUi(): LibrarySectionPageUi {
    return LibrarySectionPageUi(
        items = items.map { card -> card.librarySectionItemFromProgress() },
        nextCursor = nextCursor,
        hasMore = hasMore,
    )
}

internal data class LibrarySectionPageUi(
    val items: List<LibrarySectionItemUi> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

package com.crispy.tv.library

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.crispy.tv.backend.BackendContextResolver
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.backend.CrispyBackendClient.BaseItemDtoQueryResult
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

private fun CrispyBackendClient.MediaItem.toLibrarySectionItemUi(
    id: String?,
    addedAt: String?,
    watchedAt: String?,
    ratedAt: String?,
    ratingValue: Int?,
    lastActivityAt: String?,
    origins: List<String>,
): LibrarySectionItemUi {
    return LibrarySectionItemUi(
        id = id ?: mediaKey,
        mediaKey = mediaKey,
        mediaType = mediaType,
        title = title,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        logoUrl = logoUrl,
        poster = poster.toUiResponsiveImageSet(),
        backdrop = backdrop.toUiResponsiveImageSet(),
        logo = logo.toUiResponsiveImageSet(),
        rating = rating,
        year = releaseYear,
        genre = genres.firstOrNull(),
        maturityRating = maturityRating,
        addedAt = addedAt,
        watchedAt = watchedAt,
        ratedAt = ratedAt,
        ratingValue = ratingValue,
        lastActivityAt = lastActivityAt,
        origins = origins,
    )
}

private fun CrispyBackendClient.BaseItemDtoQueryResult.toHistorySectionPageUi(): LibrarySectionPageUi {
    return LibrarySectionPageUi(
        items = items.map { item ->
            val userData = item.userData
            val watchedAt = userData?.lastPlayedDate?.takeIf { userData.played == true }
            item.toLibrarySectionItemUi(
                id = item.mediaKey,
                addedAt = null,
                watchedAt = watchedAt,
                ratedAt = null,
                ratingValue = null,
                lastActivityAt = watchedAt,
                origins = emptyList(),
            )
        },
        nextCursor = nextCursor,
        hasMore = hasMore,
    )
}

private fun CrispyBackendClient.BaseItemDtoQueryResult.toWatchlistSectionPageUi(): LibrarySectionPageUi {
    return LibrarySectionPageUi(
        items = items.map { item ->
            item.toLibrarySectionItemUi(
                id = item.mediaKey,
                addedAt = null,
                watchedAt = null,
                ratedAt = null,
                ratingValue = null,
                lastActivityAt = null,
                origins = emptyList(),
            )
        },
        nextCursor = nextCursor,
        hasMore = hasMore,
    )
}

private fun CrispyBackendClient.BaseItemDtoQueryResult.toRatingsSectionPageUi(): LibrarySectionPageUi {
    return LibrarySectionPageUi(
        items = items.map { item ->
            val userData = item.userData
            val ratingValue = userData?.rating?.toInt()?.takeIf { it in 1..10 }
            item.toLibrarySectionItemUi(
                id = item.mediaKey,
                addedAt = null,
                watchedAt = null,
                ratedAt = null,
                ratingValue = ratingValue,
                lastActivityAt = null,
                origins = emptyList(),
            )
        },
        nextCursor = nextCursor,
        hasMore = hasMore,
    )
}

internal data class LibrarySectionPageUi(
    val items: List<LibrarySectionItemUi> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

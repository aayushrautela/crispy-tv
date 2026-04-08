package com.crispy.tv.library

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.crispy.tv.backend.BackendContextResolver
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.backend.CrispyBackendClient.CanonicalWatchCollectionResponse
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
            ).toWatchedSectionPageUi()
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

private fun CanonicalWatchCollectionResponse<CrispyBackendClient.WatchedItem>.toWatchedSectionPageUi(): LibrarySectionPageUi {
    return LibrarySectionPageUi(
        items = items.map { item ->
            LibrarySectionItemUi(
                id = item.id ?: item.media.mediaKey,
                mediaKey = item.media.mediaKey,
                mediaType = item.media.mediaType,
                title = item.media.title,
                posterUrl = item.media.posterUrl,
                backdropUrl = item.media.backdropUrl,
                addedAt = null,
                watchedAt = item.watchedAt,
                ratedAt = null,
                rating = null,
                lastActivityAt = item.lastActivityAt,
                origins = item.origins,
            )
        },
        nextCursor = pageInfo.nextCursor,
        hasMore = pageInfo.hasMore,
    )
}

private fun CanonicalWatchCollectionResponse<CrispyBackendClient.WatchlistItem>.toWatchlistSectionPageUi(): LibrarySectionPageUi {
    return LibrarySectionPageUi(
        items = items.map { item ->
            LibrarySectionItemUi(
                id = item.id ?: item.media.mediaKey,
                mediaKey = item.media.mediaKey,
                mediaType = item.media.mediaType,
                title = item.media.title,
                posterUrl = item.media.posterUrl,
                backdropUrl = item.media.backdropUrl,
                addedAt = item.addedAt,
                watchedAt = null,
                ratedAt = null,
                rating = null,
                lastActivityAt = item.addedAt,
                origins = item.origins,
            )
        },
        nextCursor = pageInfo.nextCursor,
        hasMore = pageInfo.hasMore,
    )
}

private fun CanonicalWatchCollectionResponse<CrispyBackendClient.RatingItem>.toRatingsSectionPageUi(): LibrarySectionPageUi {
    return LibrarySectionPageUi(
        items = items.map { item ->
            LibrarySectionItemUi(
                id = item.id ?: item.media.mediaKey,
                mediaKey = item.media.mediaKey,
                mediaType = item.media.mediaType,
                title = item.media.title,
                posterUrl = item.media.posterUrl,
                backdropUrl = item.media.backdropUrl,
                addedAt = null,
                watchedAt = null,
                ratedAt = item.rating.ratedAt,
                rating = item.rating.value,
                lastActivityAt = item.rating.ratedAt,
                origins = item.origins,
            )
        },
        nextCursor = pageInfo.nextCursor,
        hasMore = pageInfo.hasMore,
    )
}

internal data class LibrarySectionPageUi(
    val items: List<LibrarySectionItemUi> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

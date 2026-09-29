package com.crispy.tv.discover

import com.crispy.tv.accounts.AccountApi
import com.crispy.tv.backend.BackendApi
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.catalog.toCatalogItem
import com.crispy.tv.domain.browse.BrowseCombo
import com.crispy.tv.domain.browse.BrowseTypeResponse
import com.crispy.tv.domain.browse.mergeBrowsePage
import com.crispy.tv.domain.browse.planBrowseRequests

data class BrowsePagePayload(
    val items: List<CatalogItem> = emptyList(),
    val hasMore: Boolean = false,
)

/**
 * Merges the backend's per-type browse responses into one page of catalog items.
 *
 * Portable: it holds only the two ports (`AccountApi`, `BackendApi`) and the
 * planning/merging rules from `:core-domain`. The `Context`-taking factory
 * that used to live in this file's companion object is now
 * `backendBrowseRepository(context)` in androidMain, because building the two
 * ports needs the composition root and `Context` is Android-only. That split
 * is the same one the settings repositories use.
 */
class BackendBrowseRepository(
    private val supabase: AccountApi,
    private val backend: BackendApi,
) {
    suspend fun browsePage(
        type: String,
        genre: String? = null,
        sort: String = "popularity",
        page: Int = 0,
    ): BrowsePagePayload {
        val combo = BrowseCombo(type = type, genre = genre, sort = sort)
        val requests = planBrowseRequests(combo = combo, page = page)
        if (requests.isEmpty()) {
            return BrowsePagePayload()
        }

        val session = supabase.ensureValidSession()
            ?: throw BrowseRequiredSignInException()

        val responses = requests.associate { request ->
            val result = backend.browseTitles(
                accessToken = session.accessToken,
                type = request.type,
                genre = request.genre,
                sort = request.sort,
                page = request.page,
            )
            request.type to result
        }

        val merged = mergeBrowsePage(
            combo = combo,
            page = page,
            responses = responses.mapValues { (_, result) ->
                BrowseTypeResponse(
                    items = result.items.map { item ->
                        com.crispy.tv.domain.browse.BrowseTypeItem(
                            itemId = item.itemId,
                            type = item.mediaType,
                        )
                    },
                    hasMore = result.hasMore,
                )
            },
        )

        val items =
            requests.flatMap { request ->
                responses[request.type]?.items.orEmpty().mapNotNull { it.toCatalogItem() }
            }

        return BrowsePagePayload(
            items = items,
            hasMore = merged.hasMore,
        )
    }
}

class BrowseRequiredSignInException : IllegalStateException("Sign in to browse titles.")

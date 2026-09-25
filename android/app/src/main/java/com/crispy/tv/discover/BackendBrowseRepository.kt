package com.crispy.tv.discover

import android.content.Context
import com.crispy.tv.accounts.SupabaseAccountClient
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.backend.BackendServicesProvider
import com.crispy.tv.backend.CrispyBackendClient
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

class BackendBrowseRepository(
    private val supabase: SupabaseAccountClient,
    private val backend: CrispyBackendClient,
    private val pageSize: Int = DISCOVER_PAGE_SIZE,
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
                limit = pageSize,
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

    companion object {
        fun create(context: Context, pageSize: Int = DISCOVER_PAGE_SIZE): BackendBrowseRepository {
            val appContext = context.applicationContext
            return BackendBrowseRepository(
                supabase = SupabaseServicesProvider.accountClient(appContext),
                backend = BackendServicesProvider.backendClient(appContext),
                pageSize = pageSize,
            )
        }
    }
}

class BrowseRequiredSignInException : IllegalStateException("Sign in to browse titles.")

private const val DISCOVER_PAGE_SIZE = 60
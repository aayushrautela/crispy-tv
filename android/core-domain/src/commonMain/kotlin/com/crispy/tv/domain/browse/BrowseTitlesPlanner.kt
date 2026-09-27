package com.crispy.tv.domain.browse

data class BrowseCombo(
    val type: String,
    val genre: String?,
    val sort: String,
)

data class BrowseRequest(
    val type: String,
    val genre: String?,
    val sort: String,
    val page: Int,
)

data class BrowseTypeItem(
    val itemId: String,
    val type: String,
)

data class BrowseTypeResponse(
    val items: List<BrowseTypeItem> = emptyList(),
    val hasMore: Boolean = false,
)

data class BrowsePageResult(
    val itemIds: List<String> = emptyList(),
    val hasMore: Boolean = false,
    val nextPage: Int? = null,
)

const val BROWSE_MAX_PAGES = 20

fun planBrowseRequests(combo: BrowseCombo, page: Int): List<BrowseRequest> {
    if (page < 0 || page >= BROWSE_MAX_PAGES) return emptyList()
    val genre = normalizedBrowseGenre(combo.genre)
    return browseTypeFanOut(combo.type).map { type ->
        BrowseRequest(
            type = type,
            genre = genre,
            sort = combo.sort,
            page = page,
        )
    }
}

fun mergeBrowsePage(
    combo: BrowseCombo,
    page: Int,
    responses: Map<String, BrowseTypeResponse>,
): BrowsePageResult {
    if (page < 0 || page >= BROWSE_MAX_PAGES) {
        return BrowsePageResult()
    }

    val types = browseTypeFanOut(combo.type)
    val itemIds =
        types.flatMap { type ->
            responses[type]?.items.orEmpty().map { it.itemId }
        }
    val hasMore =
        types.any { type -> responses[type]?.hasMore ?: false } && page < BROWSE_MAX_PAGES - 1
    return BrowsePageResult(
        itemIds = itemIds,
        hasMore = hasMore,
        nextPage = if (hasMore) page + 1 else null,
    )
}

private fun browseTypeFanOut(type: String): List<String> {
    return if (type == "all") listOf("movie", "series") else listOf(type)
}

private fun normalizedBrowseGenre(genre: String?): String? {
    return genre?.trim()?.takeIf { it.isNotEmpty() }
}
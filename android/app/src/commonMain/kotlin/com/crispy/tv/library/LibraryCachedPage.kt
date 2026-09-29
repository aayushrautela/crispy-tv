package com.crispy.tv.library

import com.crispy.tv.catalog.CatalogItem

/**
 * The first page of one library section, as it was last read from or written to
 * [LibraryDiskCache].
 *
 * Lifted out of `LibraryDiskCacheStore` because that file parses and writes JSON
 * with `org.json`, which is an Android platform class and therefore unavailable in
 * a KMP `commonMain` — a settled decision in this repository. A top-level type is
 * still pinned to its file even though it is not nested in it, so [LibraryPagingSource]
 * could not name it until it lived here.
 */
data class LibraryCachedPage(
    val items: List<CatalogItem>,
    val nextCursor: String?,
    val hasMore: Boolean,
    val appliedGenerationMs: Long?,
)

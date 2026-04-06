package com.crispy.tv.search

import com.crispy.tv.catalog.CatalogItem

data class SearchResultsPayload(
    val items: List<SearchCatalogItem> = emptyList(),
    val message: String? = null,
)

typealias SearchCatalogItem = CatalogItem

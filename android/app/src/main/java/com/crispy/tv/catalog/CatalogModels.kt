package com.crispy.tv.catalog

import androidx.compose.runtime.Immutable

@Immutable
data class CatalogSectionRef(
    val title: String,
    val catalogId: String,
    val mediaType: String,
    val addonId: String,
    val baseUrl: String,
    val encodedAddonQuery: String?
) {
    val key: String
        get() = "${mediaType.lowercase()}:$catalogId"
}

@Immutable
data class CatalogItem(
    val id: String,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val addonId: String,
    val type: String,
    val rating: String? = null,
    val year: String? = null,
    val genre: String? = null
)

@Immutable
data class CatalogPageResult(
    val items: List<CatalogItem> = emptyList(),
    val statusMessage: String = "",
    val attemptedUrls: List<String> = emptyList()
)

@Immutable
data class DiscoverCatalogRef(
    val section: CatalogSectionRef,
    val addonName: String,
    val genres: List<String> = emptyList()
) {
    val key: String
        get() = "${section.addonId.lowercase()}:${section.mediaType.lowercase()}:${section.catalogId.lowercase()}"
}

package com.crispy.tv.catalog

import androidx.compose.runtime.Immutable
import java.util.Locale

@Immutable
data class CatalogSectionRef(
    val title: String,
    val catalogId: String
) {
    val key: String
        get() = catalogId.trim().lowercase(Locale.US)
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
        get() = section.key
}

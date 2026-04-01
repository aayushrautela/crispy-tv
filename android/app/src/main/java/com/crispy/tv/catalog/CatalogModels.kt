package com.crispy.tv.catalog

import androidx.compose.runtime.Immutable
import com.crispy.tv.domain.home.HomeCatalogPresentation
import com.crispy.tv.domain.home.HomeCatalogSource
import java.util.Locale

@Immutable
data class CatalogSectionRef(
    val catalogId: String,
    val source: HomeCatalogSource,
    val presentation: HomeCatalogPresentation,
    val variantKey: String = "default",
    val name: String = "",
    val heading: String = "",
    val title: String = "",
    val subtitle: String = "",
    val previewItems: List<CatalogItem> = emptyList(),
) {
    val key: String = catalogId.trim().lowercase(Locale.US)

    val displayTitle: String
        get() = heading.ifBlank { title.ifBlank { name.ifBlank { catalogId } } }
}

@Immutable
data class CatalogItem(
    val id: String,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val logoUrl: String? = null,
    val addonId: String,
    val type: String,
    val rating: String? = null,
    val year: String? = null,
    val genre: String? = null,
    val description: String? = null,
    val provider: String,
    val providerId: String,
    val detailsContentId: String? = null,
    val detailsMediaType: String? = null,
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

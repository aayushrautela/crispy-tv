package com.crispy.tv.catalog

import androidx.compose.runtime.Immutable
import com.crispy.tv.domain.home.HomeCatalogPresentation
import com.crispy.tv.domain.home.HomeCatalogSource
import com.crispy.tv.images.ResponsiveImageSet

@Immutable
data class CatalogSectionRef(
    val catalogId: String,
    val source: HomeCatalogSource,
    val presentation: HomeCatalogPresentation,
    val layout: String = "",
    val variantKey: String = "default",
    val kind: String = "",
    val name: String = "",
    val heading: String = "",
    val title: String = "",
    val subtitle: String = "",
    val previewItems: List<CatalogItem> = emptyList(),
) {
    // Was `lowercase(Locale.US)`, which pinned this file to the JVM. Kotlin's
    // `lowercase()` is locale-invariant by definition, so for the ASCII slugs an
    // add-on publishes the two are identical; where they could differ is a
    // non-ASCII catalog id, and there the invariant form is the predictable one
    // (it is also what a Turkish-locale device produced before, since a catalog
    // id never passed through a user-facing locale).
    val key: String = catalogId.trim().lowercase()

    val displayTitle: String
        get() = heading.ifBlank { title.ifBlank { name.ifBlank { catalogId } } }
}

@Immutable
data class CatalogItem(
    val id: String,
    val itemId: String,
    val title: String,
    val artworkUrl: String?,
    val logoUrl: String? = null,
    val artwork: ResponsiveImageSet? = ResponsiveImageSet.fromSingle(artworkUrl),
    val logo: ResponsiveImageSet? = ResponsiveImageSet.fromSingle(logoUrl),
    val addonId: String,
    val type: String,
    val rating: String? = null,
    val year: String? = null,
    val genre: String? = null,
    val description: String? = null,
    val maturityRating: String? = null,
    val liked: Boolean? = null,
    val addedAt: String? = null,
    val watchedAt: String? = null,
    val ratedAt: String? = null,
    val lastActivityAt: String? = null,
    val episodeCount: Int? = null,
)

@Immutable
data class CatalogPageResult(
    val items: List<CatalogItem> = emptyList(),
    val statusMessage: String = "",
    val attemptedUrls: List<String> = emptyList()
)

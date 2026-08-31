package com.crispy.tv.catalog

import com.crispy.tv.addons.mapping.normalizedCatalogMediaType
import com.crispy.tv.addons.util.formatRating
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.images.toUiResponsiveImageSet

fun CrispyBackendClient.ClientMediaCard.toCatalogItem(): CatalogItem? {
    val itemTitle = title.trim().takeIf { it.isNotBlank() } ?: return null
    val normalizedItemId = itemId.trim().takeIf { it.isNotBlank() } ?: return null
    val normalizedType = normalizedCatalogMediaType()
    val normalizedArtworkUrl = images.artwork.medium ?: images.artwork.large ?: images.artwork.small
    if (normalizedArtworkUrl.isNullOrBlank()) return null
    return CatalogItem(
        id = normalizedItemId,
        itemId = normalizedItemId,
        title = itemTitle,
        artworkUrl = normalizedArtworkUrl,
        artwork = images.artwork.toUiResponsiveImageSet(),
        addonId = "backend",
        type = normalizedType,
        rating = formatRating(rating),
        year = year?.toString() ?: releaseDate?.take(4),
        genre = genres.firstOrNull(),
        description = overview,
    )
}

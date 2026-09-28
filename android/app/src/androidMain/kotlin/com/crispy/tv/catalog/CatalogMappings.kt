package com.crispy.tv.catalog

import com.crispy.tv.addons.mapping.normalizedCatalogMediaType
import com.crispy.tv.addons.util.formatRating
import com.crispy.tv.backend.ClientMediaCard

fun ClientMediaCard.toCatalogItem(): CatalogItem? {
    val itemTitle = title.trim().takeIf { it.isNotBlank() } ?: return null
    val normalizedItemId = itemId.trim().takeIf { it.isNotBlank() } ?: return null
    val normalizedType = normalizedCatalogMediaType()
    val normalizedArtworkUrl = images.artwork.medium ?: images.artwork.high ?: images.artwork.low
    if (normalizedArtworkUrl.isNullOrBlank()) return null
    val logoUrl = images.logo.medium ?: images.logo.high ?: images.logo.low
    return CatalogItem(
        id = normalizedItemId,
        itemId = normalizedItemId,
        title = itemTitle,
        artworkUrl = normalizedArtworkUrl,
        logoUrl = logoUrl,
artwork = images.artwork,
logo = images.logo,
        addonId = "backend",
        type = normalizedType,
        rating = formatRating(rating),
        year = year?.toString() ?: releaseDate?.take(4),
        genre = genres.firstOrNull(),
        description = overview,
    )
}

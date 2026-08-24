package com.crispy.tv.catalog

import com.crispy.tv.addons.mapping.normalizedCatalogMediaType
import com.crispy.tv.addons.util.formatRating
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.images.toUiResponsiveImageSet

fun CrispyBackendClient.MetadataCardView.toCatalogItem(): CatalogItem? {
    val itemTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: subtitle?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalizedItemId = itemId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalizedType = normalizedCatalogMediaType()
    val normalizedPosterUrl = images.posterUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return CatalogItem(
        id = normalizedItemId,
        itemId = normalizedItemId,
        title = itemTitle,
        posterUrl = normalizedPosterUrl,
        backdropUrl = images.backdropUrl,
        logoUrl = images.logoUrl,
        poster = images.poster.toUiResponsiveImageSet(),
        backdrop = images.backdrop.toUiResponsiveImageSet(),
        logo = images.logo.toUiResponsiveImageSet(),
        addonId = "backend",
        type = normalizedType,
        rating = formatRating(rating),
        year = releaseYear?.toString() ?: releaseDate?.take(4),
        genre = genre,
        description = summary ?: overview,
    )
}

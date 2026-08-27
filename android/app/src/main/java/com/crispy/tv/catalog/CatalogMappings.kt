package com.crispy.tv.catalog

import com.crispy.tv.addons.mapping.normalizedCatalogMediaType
import com.crispy.tv.addons.util.formatRating
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.images.toUiResponsiveImageSet

fun CrispyBackendClient.MetadataCardView.toCatalogItem(): CatalogItem? {
    val itemTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: subtitle?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalizedItemId = itemId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalizedType = normalizedCatalogMediaType()
    val normalizedPosterUrl = images.poster.medium ?: images.poster.large ?: images.poster.small
    if (normalizedPosterUrl.isNullOrBlank()) return null
    return CatalogItem(
        id = normalizedItemId,
        itemId = normalizedItemId,
        title = itemTitle,
        posterUrl = normalizedPosterUrl,
        backdropUrl = images.backdrop.medium ?: images.backdrop.large ?: images.backdrop.small,
        logoUrl = images.logo.medium ?: images.logo.large ?: images.logo.small,
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

fun CrispyBackendClient.MediaItem.toCatalogItem(): CatalogItem? {
    val itemTitle = title.trim().takeIf { it.isNotBlank() } ?: return null
    val normalizedItemId = itemId.trim().takeIf { it.isNotBlank() } ?: return null
    val normalizedType = when (itemType.trim()) {
        "movie" -> "movie"
        "show" -> "show"
        "tv" -> "show"
        "season" -> "season"
        "episode" -> "episode"
        else -> return null
    }
    val normalizedPosterUrl = poster.medium ?: poster.large ?: poster.small
    return CatalogItem(
        id = normalizedItemId,
        itemId = normalizedItemId,
        title = itemTitle,
        posterUrl = normalizedPosterUrl,
        backdropUrl = backdrop.medium ?: backdrop.large ?: backdrop.small,
        logoUrl = logo.medium ?: logo.large ?: logo.small,
        poster = poster.toUiResponsiveImageSet(),
        backdrop = backdrop.toUiResponsiveImageSet(),
        logo = logo.toUiResponsiveImageSet(),
        addonId = "backend",
        type = normalizedType,
        rating = formatRating(rating),
        year = releaseYear?.toString() ?: releaseDate?.take(4),
        genre = genres.firstOrNull(),
        description = overview,
    )
}

package com.crispy.tv.domain.catalog

data class CatalogFilter(
    val key: String,
    val value: String
)

data class TmdbSearchResultInput(
    val mediaType: String,
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    val releaseDate: String? = null,
    val firstAirDate: String? = null,
    val posterPath: String? = null,
    val profilePath: String? = null,
    val voteAverage: Double? = null
)

data class NormalizedSearchItem(
    val mediaType: String,
    val itemKey: String,
    val title: String,
    val year: Int?,
    val imageUrl: String?,
    val rating: Double?
)

private const val TMDB_IMAGE_BASE_URL = "https://image.tmdb.org/t/p/"

fun normalizeTmdbSearchResults(results: List<TmdbSearchResultInput>): List<NormalizedSearchItem> {
    val seenKeys = linkedSetOf<String>()
    val normalized = mutableListOf<NormalizedSearchItem>()

    results.forEach { result ->
        val rawMediaType = result.mediaType.trim().lowercase()
        val type =
            when (rawMediaType) {
                "movie" -> "movie"
                "tv" -> "series"
                "person" -> "person"
                else -> return@forEach
            }

        val tmdbId = result.id
        if (tmdbId <= 0) {
            return@forEach
        }

        val itemKey = "tmdb:$type:$tmdbId"
        val key = itemKey
        if (!seenKeys.add(key)) {
            return@forEach
        }

        val primaryTitle = if (rawMediaType == "movie") result.title else result.name
        val fallbackTitle = if (rawMediaType == "movie") result.name else result.title
        val title = primaryTitle?.trim().orEmpty().ifBlank { fallbackTitle?.trim().orEmpty() }
        if (title.isBlank()) {
            return@forEach
        }

        val year =
            when (rawMediaType) {
                "movie" -> parseYear(result.releaseDate)
                "tv" -> parseYear(result.firstAirDate)
                else -> null
            }

        val imagePath =
            when (rawMediaType) {
                "person" -> result.profilePath
                else -> result.posterPath
            }
        val imageSize = if (rawMediaType == "person") "h632" else "w500"
        val imageUrl = tmdbImageUrl(imagePath, imageSize)

        val rating = result.voteAverage?.takeIf { it.isFinite() }

        normalized +=
            NormalizedSearchItem(
                mediaType = type,
                itemKey = itemKey,
                title = title,
                year = year,
                imageUrl = imageUrl,
                rating = rating
            )
    }

    return normalized
}

private fun parseYear(value: String?): Int? {
    val raw = value?.trim().orEmpty()
    if (raw.length < 4) {
        return null
    }
    val year = raw.take(4).toIntOrNull() ?: return null
    return year.takeIf { it in 1800..3000 }
}

private fun tmdbImageUrl(path: String?, size: String): String? {
    val raw = path?.trim().orEmpty()
    if (raw.isEmpty()) {
        return null
    }
    val normalizedPath = if (raw.startsWith('/')) raw else "/$raw"
    return "$TMDB_IMAGE_BASE_URL$size$normalizedPath"
}

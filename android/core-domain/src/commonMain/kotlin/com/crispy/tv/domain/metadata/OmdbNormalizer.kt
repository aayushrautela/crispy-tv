package com.crispy.tv.domain.metadata

import java.util.Locale

data class OmdbRatingInput(
    val source: String?,
    val value: String?,
)

data class OmdbRating(
    val source: String,
    val value: String,
)

data class OmdbDetails(
    val ratings: List<OmdbRating> = emptyList(),
    val metascore: String? = null,
    val imdbRating: String? = null,
    val imdbVotes: String? = null,
    val type: String? = null,
)

fun normalizeOmdbImdbId(value: String?): String? {
    val normalized = value?.trim()?.lowercase(Locale.US).orEmpty()
    return normalized.takeIf { IMDB_ID_REGEX.matches(it) }
}

fun normalizeOmdbDetails(
    ratings: List<OmdbRatingInput>,
    metascore: String?,
    imdbRating: String?,
    imdbVotes: String?,
    type: String?,
): OmdbDetails {
    val normalizedMetascore = metascore.normalizedOmdbField()
    val normalizedImdbRating = imdbRating.normalizedOmdbField()
    val normalizedImdbVotes = imdbVotes.normalizedOmdbField()
    val normalizedType = type.normalizedOmdbField()

    val dedupedRatings = mutableListOf<OmdbRating>()
    val seenSources = linkedSetOf<String>()

    ratings.forEach { rating ->
        val source = rating.source.normalizedOmdbField() ?: return@forEach
        val value = rating.value.normalizedOmdbField() ?: return@forEach
        val normalizedSource = source.lowercase(Locale.US)
        if (!seenSources.add(normalizedSource)) {
            return@forEach
        }
        dedupedRatings += OmdbRating(source = source, value = value)
    }

    if (normalizedImdbRating != null && seenSources.add(INTERNET_MOVIE_DATABASE_SOURCE.lowercase(Locale.US))) {
        dedupedRatings += OmdbRating(
            source = INTERNET_MOVIE_DATABASE_SOURCE,
            value = "$normalizedImdbRating/10",
        )
    }

    if (normalizedMetascore != null && seenSources.add(METACRITIC_SOURCE.lowercase(Locale.US))) {
        dedupedRatings += OmdbRating(
            source = METACRITIC_SOURCE,
            value = "$normalizedMetascore/100",
        )
    }

    return OmdbDetails(
        ratings = dedupedRatings,
        metascore = normalizedMetascore,
        imdbRating = normalizedImdbRating,
        imdbVotes = normalizedImdbVotes,
        type = normalizedType,
    )
}

private fun String?.normalizedOmdbField(): String? {
    val normalized = this?.trim().orEmpty()
    if (normalized.isBlank() || normalized.equals("N/A", ignoreCase = true)) {
        return null
    }
    return normalized
}

private const val INTERNET_MOVIE_DATABASE_SOURCE = "Internet Movie Database"
private const val METACRITIC_SOURCE = "Metacritic"
private val IMDB_ID_REGEX = Regex("^tt\\d+$", RegexOption.IGNORE_CASE)

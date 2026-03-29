package com.crispy.tv.metadata.tmdb

import com.crispy.tv.home.MediaDetails
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.ratings.formatRating
import java.util.Locale
import org.json.JSONObject

private val CERTIFICATION_COUNTRY_PRIORITY = listOf("US", "GB")

internal fun JSONObject.toFallbackMediaDetails(
    normalizedContentId: String,
    mediaType: MetadataLabMediaType,
    imdbId: String?,
    preferredLanguage: String,
): MediaDetails {
    val title = resolveTitle(this, mediaType) ?: normalizedContentId
    val posterUrl = TmdbApi.imageUrl(optStringNonBlank("poster_path"), "w500")
    val backdropUrl = TmdbApi.imageUrl(optStringNonBlank("backdrop_path"), "w780")
    val logoUrl = parseBestTitleLogoUrl(optJSONObject("images"), preferredLanguage)
    val genres =
        buildList {
            val genreArray = optJSONArray("genres")
            if (genreArray != null) {
                for (index in 0 until genreArray.length()) {
                    genreArray.optJSONObject(index)?.optStringNonBlank("name")?.let(::add)
                }
            }
        }
    val year =
        when (mediaType) {
            MetadataLabMediaType.MOVIE -> optStringNonBlank("release_date")
            MetadataLabMediaType.SERIES -> optStringNonBlank("first_air_date")
            MetadataLabMediaType.ANIME -> optStringNonBlank("first_air_date")
        }?.take(4)
    val runtime =
        when (mediaType) {
            MetadataLabMediaType.MOVIE -> optIntOrNull("runtime")?.let { "$it min" }
            MetadataLabMediaType.SERIES -> {
                val runtimes = optJSONArray("episode_run_time")
                val first =
                    runtimes?.let { array ->
                        (0 until array.length()).asSequence().map { array.optInt(it) }.firstOrNull { it > 0 }
                    }
                first?.let { "$it min" }
            }
            MetadataLabMediaType.ANIME -> {
                val runtimes = optJSONArray("episode_run_time")
                val first =
                    runtimes?.let { array ->
                        (0 until array.length()).asSequence().map { array.optInt(it) }.firstOrNull { it > 0 }
                    }
                first?.let { "$it min" }
            }
        }
    val credits = optJSONObject("credits")
    val cast = parseCastMembers(credits).map { it.name }
    val directors =
        if (mediaType == MetadataLabMediaType.MOVIE) {
            parseMovieDirectors(credits).take(6)
        } else {
            emptyList()
        }
    val creators =
        if (mediaType != MetadataLabMediaType.MOVIE) {
            parseSeriesCreators(this).take(12)
        } else {
            emptyList()
        }

    return MediaDetails(
        id = normalizedContentId,
        imdbId = imdbId,
        mediaType = mediaType.toCatalogType(),
        title = title,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        logoUrl = logoUrl,
        description = optStringNonBlank("overview"),
        genres = genres,
        year = year,
        runtime = runtime,
        certification = parseCertification(this, mediaType),
        rating = formatRating(optDoubleOrNull("vote_average")),
        cast = cast,
        directors = directors,
        creators = creators,
        videos = emptyList(),
        tmdbId = optIntOrNull("id"),
        showTmdbId = optIntOrNull("id").takeIf { mediaType != MetadataLabMediaType.MOVIE },
        addonId = "tmdb",
    )
}

private fun parseCertification(
    details: JSONObject,
    mediaType: MetadataLabMediaType,
): String? {
    return when (mediaType) {
        MetadataLabMediaType.MOVIE -> parseMovieCertification(details)
        MetadataLabMediaType.SERIES -> parseSeriesCertification(details)
        MetadataLabMediaType.ANIME -> parseSeriesCertification(details)
    }
}

private fun parseMovieCertification(details: JSONObject): String? {
    val releaseDates = details.optJSONObject("release_dates")?.optJSONArray("results")?.toJsonObjectList() ?: return null
    return releaseDates.firstCountryCertification()
}

private fun parseSeriesCertification(details: JSONObject): String? {
    val contentRatings = details.optJSONObject("content_ratings")?.optJSONArray("results")?.toJsonObjectList() ?: return null
    return contentRatings.firstCountryTvCertification()
}

private fun List<JSONObject>.firstCountryCertification(): String? {
    for (countryCode in CERTIFICATION_COUNTRY_PRIORITY) {
        firstOrNull { it.optStringNonBlank("iso_3166_1").equals(countryCode, ignoreCase = true) }
            ?.firstMovieCertification()
            ?.let { return it }
    }
    for (entry in this) {
        entry.firstMovieCertification()?.let { return it }
    }
    return null
}

private fun List<JSONObject>.firstCountryTvCertification(): String? {
    for (countryCode in CERTIFICATION_COUNTRY_PRIORITY) {
        val certification =
            firstOrNull { it.optStringNonBlank("iso_3166_1").equals(countryCode, ignoreCase = true) }
                ?.optStringNonBlank("rating")
        if (!certification.isNullOrBlank()) {
            return certification
        }
    }
    return firstNotNullOfOrNull { it.optStringNonBlank("rating") }
}

private fun JSONObject.firstMovieCertification(): String? {
    val entries = optJSONArray("release_dates")?.toJsonObjectList() ?: return null
    for (entry in entries) {
        val certification = entry.optStringNonBlank("certification")
        if (!certification.isNullOrBlank()) {
            return certification
        }
    }
    return null
}

private fun parseBestTitleLogoUrl(images: JSONObject?, preferredLanguage: String): String? {
    val logos = images?.optJSONArray("logos")?.toJsonObjectList() ?: return null
    if (logos.isEmpty()) return null

    val language = preferredLanguage.substringBefore('-').trim().ifBlank { "en" }

    fun languageScore(iso6391: String?): Int {
        return when {
            iso6391.equals(language, ignoreCase = true) -> 0
            iso6391.equals("en", ignoreCase = true) -> 1
            iso6391 == null -> 2
            else -> 3
        }
    }

    val selected =
        logos.mapNotNull { logo ->
            val filePath = logo.optStringNonBlank("file_path") ?: return@mapNotNull null
            LogoCandidate(
                filePath = filePath,
                languageScore = languageScore(logo.optStringNonBlank("iso_639_1")),
                voteAverage = logo.optDoubleOrNull("vote_average") ?: 0.0,
                voteCount = logo.optInt("vote_count", 0),
                width = logo.optInt("width", 0),
                height = logo.optInt("height", 0),
            )
        }.minWithOrNull(
            compareBy<LogoCandidate> { it.languageScore }
                .thenByDescending { it.voteAverage }
                .thenByDescending { it.voteCount }
                .thenByDescending { it.width }
                .thenByDescending { it.height }
                .thenBy { it.filePath.lowercase(Locale.US) }
        )

    return selected?.let { TmdbApi.imageUrl(it.filePath, "w500") }
}

private data class LogoCandidate(
    val filePath: String,
    val languageScore: Int,
    val voteAverage: Double,
    val voteCount: Int,
    val width: Int,
    val height: Int,
)

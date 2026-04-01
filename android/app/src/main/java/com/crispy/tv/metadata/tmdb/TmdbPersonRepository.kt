package com.crispy.tv.metadata.tmdb

import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.ratings.formatRating
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal data class TmdbPersonProfile(
    val tmdbPersonId: Int,
    val name: String,
    val knownForDepartment: String?,
    val biography: String?,
    val birthday: String?,
    val placeOfBirth: String?,
    val profileUrl: String?,
    val imdbId: String?,
    val instagramId: String?,
    val twitterId: String?,
    val knownFor: List<CatalogItem>,
)

internal class TmdbPersonRepository(
    private val tmdbClient: TmdbJsonClient,
) {
    suspend fun load(personId: String, locale: Locale = Locale.getDefault()): TmdbPersonProfile? {
        val tmdbPersonId = extractPersonId(personId) ?: return null
        val json =
            tmdbClient.getJson(
                path = "person/$tmdbPersonId",
                query = mapOf(
                    "append_to_response" to "combined_credits,external_ids",
                    "language" to locale.toTmdbLanguageTag(),
                ),
            ) ?: return null

        return TmdbPersonProfile(
            tmdbPersonId = tmdbPersonId,
            name = json.optStringNonBlank("name") ?: return null,
            knownForDepartment = json.optStringNonBlank("known_for_department"),
            biography = json.optStringNonBlank("biography"),
            birthday = json.optStringNonBlank("birthday"),
            placeOfBirth = json.optStringNonBlank("place_of_birth"),
            profileUrl = TmdbApi.imageUrl(json.optStringNonBlank("profile_path"), "h632"),
            imdbId = extractImdbId(json.optJSONObject("external_ids")?.optStringNonBlank("imdb_id")),
            instagramId = json.optJSONObject("external_ids")?.optStringNonBlank("instagram_id"),
            twitterId = json.optJSONObject("external_ids")?.optStringNonBlank("twitter_id"),
            knownFor = parseKnownFor(json),
        )
    }

    private fun parseKnownFor(json: JSONObject): List<CatalogItem> {
        val credits = json.optJSONObject("combined_credits") ?: return emptyList()
        val cast = credits.optJSONArray("cast") ?: JSONArray()

        data class Entry(val item: CatalogItem, val popularity: Double)

        val seenKeys = linkedSetOf<String>()
        val entries =
            buildList {
                for (index in 0 until cast.length()) {
                    val credit = cast.optJSONObject(index) ?: continue
                    val mediaType = credit.optStringNonBlank("media_type")?.lowercase(Locale.US) ?: continue
                    val type =
                        when (mediaType) {
                            "movie" -> "movie"
                            "tv" -> "series"
                            else -> continue
                        }

                    val tmdbId = credit.optInt("id", 0)
                    if (tmdbId <= 0) continue

                    val key = "$type:$tmdbId"
                    if (!seenKeys.add(key)) continue

                    val title =
                        if (mediaType == "movie") {
                            credit.optStringNonBlank("title") ?: credit.optStringNonBlank("name")
                        } else {
                            credit.optStringNonBlank("name") ?: credit.optStringNonBlank("title")
                        } ?: continue

                    val year =
                        when (mediaType) {
                            "movie" -> parseYear(credit.optStringNonBlank("release_date"))
                            else -> parseYear(credit.optStringNonBlank("first_air_date"))
                        }
                    val popularity = credit.optDoubleOrNull("popularity") ?: 0.0

                    add(
                        Entry(
                            item = CatalogItem(
                                id = "tmdb:$tmdbId",
                                title = title,
                                posterUrl = TmdbApi.imageUrl(credit.optStringNonBlank("poster_path"), "w500"),
                                backdropUrl = null,
                                addonId = "tmdb",
                                type = type,
                                rating = formatRating(credit.optDoubleOrNull("vote_average")),
                                year = year?.toString(),
                                genre = null,
                                provider = "tmdb",
                                providerId = tmdbId.toString(),
                            ),
                            popularity = popularity,
                        )
                    )
                }
            }

        return entries.sortedByDescending { it.popularity }.take(20).map { it.item }
    }

    private fun extractPersonId(value: String): Int? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        trimmed.toIntOrNull()?.takeIf { it > 0 }?.let { return it }
        extractTmdbId(trimmed)?.let { return it }
        return ANY_DIGITS_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
    }

    private fun parseYear(value: String?): Int? {
        val raw = value?.trim().orEmpty()
        if (raw.length < 4) return null
        val year = raw.take(4).toIntOrNull() ?: return null
        return year.takeIf { it in 1800..3000 }
    }

    private companion object {
        private val ANY_DIGITS_REGEX = Regex("\\b(\\d+)\\b")
    }
}

package com.crispy.tv.metadata.tmdb

import com.crispy.tv.domain.metadata.MetadataRecord
import com.crispy.tv.domain.metadata.MetadataSeason
import com.crispy.tv.player.MetadataLabMediaType
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

internal data class TmdbMetadataRecordResult(
    val title: String,
    val record: MetadataRecord,
)

internal class TmdbMetadataRecordRepository(
    private val client: TmdbJsonClient,
    private val identityService: TmdbIdentityService,
) {
    suspend fun fetchMeta(
        mediaType: MetadataLabMediaType,
        contentId: String,
        locale: Locale = Locale.US,
    ): TmdbMetadataRecordResult? {
        val language = locale.toTmdbLanguageTag()
        val resolved =
            identityService.resolveTmdb(
                contentId = contentId,
                preferredMediaType = mediaType,
                language = language,
            ) ?: return null

        val tmdbId = resolved.tmdbId
        val resolvedMediaType = resolved.mediaType
        val details = fetchDetails(resolvedMediaType, tmdbId, language) ?: return null

        val (credits, recommendations, externalIds) =
            coroutineScope {
                val creditsDeferred = async { fetchCredits(resolvedMediaType, tmdbId, language) }
                val recommendationsDeferred = async { fetchRecommendations(resolvedMediaType, tmdbId, language) }
                val externalIdsDeferred =
                    if (resolvedMediaType == MetadataLabMediaType.SERIES) {
                        async { fetchExternalIds(resolvedMediaType, tmdbId) }
                    } else {
                        null
                    }

                Triple(
                    creditsDeferred.await(),
                    recommendationsDeferred.await(),
                    externalIdsDeferred?.await(),
                )
            }

        val imdbId =
            when (resolvedMediaType) {
                MetadataLabMediaType.MOVIE -> details.optStringNonBlank("imdb_id")
                MetadataLabMediaType.SERIES -> externalIds?.optStringNonBlank("imdb_id")
            }?.let(::extractImdbId)

        val castPairs = parseCast(credits)
        val castNames = castPairs.map { it.first }
        val castWithDetails = castPairs.mapNotNull { (name, character) -> if (character != null) "$name as $character" else name }
        val directors =
            if (resolvedMediaType == MetadataLabMediaType.MOVIE) {
                parseMovieDirectors(credits)
            } else {
                parseSeriesCreators(details)
            }
        val similar = parseRecommendations(recommendations)
        val collectionItems =
            if (resolvedMediaType == MetadataLabMediaType.MOVIE) {
                parseMovieCollection(details)
            } else {
                emptyList()
            }
        val seasons =
            if (resolvedMediaType == MetadataLabMediaType.SERIES) {
                parseSeriesSeasons(details, tmdbId)
            } else {
                emptyList()
            }

        val title =
            when (resolvedMediaType) {
                MetadataLabMediaType.MOVIE -> {
                    details.optStringNonBlank("title") ?: details.optStringNonBlank("original_title")
                }
                MetadataLabMediaType.SERIES -> {
                    details.optStringNonBlank("name") ?: details.optStringNonBlank("original_name")
                }
            } ?: "tmdb:$tmdbId"

        val record = MetadataRecord(
            id = "tmdb:$tmdbId",
            imdbId = imdbId,
            cast = castNames,
            director = directors,
            castWithDetails = castWithDetails,
            similar = similar,
            collectionItems = collectionItems,
            seasons = seasons,
            videos = emptyList(),
        )

        return TmdbMetadataRecordResult(title = title, record = record)
    }

    private suspend fun fetchDetails(mediaType: MetadataLabMediaType, tmdbId: Int, language: String): JSONObject? {
        return client.getJson(
            path = "${mediaType.pathSegment()}/$tmdbId",
            query = mapOf("language" to language),
        )
    }

    private suspend fun fetchCredits(mediaType: MetadataLabMediaType, tmdbId: Int, language: String): JSONObject? {
        return client.getJson(
            path = "${mediaType.pathSegment()}/$tmdbId/credits",
            query = mapOf("language" to language),
        )
    }

    private suspend fun fetchRecommendations(mediaType: MetadataLabMediaType, tmdbId: Int, language: String): JSONObject? {
        return client.getJson(
            path = "${mediaType.pathSegment()}/$tmdbId/recommendations",
            query = mapOf(
                "language" to language,
                "page" to "1",
            ),
        )
    }

    private suspend fun fetchExternalIds(mediaType: MetadataLabMediaType, tmdbId: Int): JSONObject? {
        return client.getJson(path = "${mediaType.pathSegment()}/$tmdbId/external_ids")
    }

    private fun parseCast(credits: JSONObject?): List<Pair<String, String?>> {
        val castArray = credits?.optJSONArray("cast") ?: return emptyList()
        val output = mutableListOf<Pair<String, String?>>()
        for (index in 0 until minOf(castArray.length(), 12)) {
            val actor = castArray.optJSONObject(index) ?: continue
            val name = actor.optStringNonBlank("name") ?: continue
            output += name to actor.optStringNonBlank("character")
        }
        return output
    }

    private fun parseMovieDirectors(credits: JSONObject?): List<String> {
        val crewArray = credits?.optJSONArray("crew") ?: return emptyList()
        val output = mutableListOf<String>()
        for (index in 0 until crewArray.length()) {
            val member = crewArray.optJSONObject(index) ?: continue
            val job = member.optStringNonBlank("job")
            if (!job.equals("Director", ignoreCase = true)) continue
            val name = member.optStringNonBlank("name") ?: continue
            output += name
        }
        return output.distinct()
    }

    private fun parseSeriesCreators(details: JSONObject): List<String> {
        val creators = details.optJSONArray("created_by") ?: return emptyList()
        val output = mutableListOf<String>()
        for (index in 0 until creators.length()) {
            val name = creators.optJSONObject(index)?.optStringNonBlank("name") ?: continue
            output += name
        }
        return output.distinct()
    }

    private fun parseRecommendations(recommendations: JSONObject?): List<String> {
        val results = recommendations?.optJSONArray("results") ?: return emptyList()
        val output = mutableListOf<String>()
        for (index in 0 until minOf(results.length(), 20)) {
            val entry = results.optJSONObject(index) ?: continue
            val id = entry.optInt("id", -1)
            if (id > 0) {
                output += "tmdb:$id"
            }
        }
        return output
    }

    private fun parseMovieCollection(details: JSONObject): List<String> {
        val collection = details.optJSONObject("belongs_to_collection") ?: return emptyList()
        val collectionId = collection.optInt("id", -1)
        return if (collectionId > 0) {
            listOf("tmdb:collection:$collectionId")
        } else {
            emptyList()
        }
    }

    private fun parseSeriesSeasons(details: JSONObject, tmdbId: Int): List<MetadataSeason> {
        val seasons = details.optJSONArray("seasons") ?: return emptyList()
        val output = mutableListOf<MetadataSeason>()
        for (index in 0 until seasons.length()) {
            val seasonObject = seasons.optJSONObject(index) ?: continue
            val seasonNumber = seasonObject.optInt("season_number", -1)
            if (seasonNumber <= 0) continue
            val episodeCount = seasonObject.optInt("episode_count", -1)
            if (episodeCount <= 0) continue

            output += MetadataSeason(
                id = "tmdb:$tmdbId:season:$seasonNumber",
                name = seasonObject.optStringNonBlank("name") ?: "Season $seasonNumber",
                overview = seasonObject.optStringNonBlank("overview") ?: "",
                seasonNumber = seasonNumber,
                episodeCount = episodeCount,
                airDate = seasonObject.optStringNonBlank("air_date"),
            )
        }
        return output
    }
}

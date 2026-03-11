package com.crispy.tv.metadata.tmdb

import com.crispy.tv.player.MetadataLabMediaType
import org.json.JSONObject

internal class TmdbTitleRemoteDataSource(
    private val client: TmdbJsonClient,
) {
    suspend fun fetchEnrichmentDetails(
        mediaType: MetadataLabMediaType,
        tmdbId: Int,
        language: String,
    ): JSONObject? {
        return client.getJson(
            path = "${mediaType.pathSegment()}/$tmdbId",
            query = mapOf(
                "language" to language,
                "append_to_response" to enrichmentAppendToResponse(mediaType),
            ),
        )
    }

    suspend fun fetchArtworkDetails(
        mediaType: MetadataLabMediaType,
        tmdbId: Int,
        language: String,
    ): JSONObject? {
        return client.getJson(
            path = "${mediaType.pathSegment()}/$tmdbId",
            query = buildMap {
                put("language", language)
                put("include_image_language", buildIncludeImageLanguage(language))
                put(
                    "append_to_response",
                    if (mediaType == MetadataLabMediaType.MOVIE) {
                        "images,release_dates"
                    } else {
                        "images,external_ids,content_ratings"
                    },
                )
            },
        )
    }

    suspend fun fetchMetadataDetails(
        mediaType: MetadataLabMediaType,
        tmdbId: Int,
        language: String,
    ): JSONObject? {
        return client.getJson(
            path = "${mediaType.pathSegment()}/$tmdbId",
            query = mapOf("language" to language),
        )
    }

    suspend fun fetchCredits(
        mediaType: MetadataLabMediaType,
        tmdbId: Int,
        language: String,
    ): JSONObject? {
        return client.getJson(
            path = "${mediaType.pathSegment()}/$tmdbId/credits",
            query = mapOf("language" to language),
        )
    }

    suspend fun fetchRecommendations(
        mediaType: MetadataLabMediaType,
        tmdbId: Int,
        language: String,
    ): JSONObject? {
        return client.getJson(
            path = "${mediaType.pathSegment()}/$tmdbId/recommendations",
            query = mapOf(
                "language" to language,
                "page" to "1",
            ),
        )
    }

    suspend fun fetchExternalIds(
        mediaType: MetadataLabMediaType,
        tmdbId: Int,
    ): JSONObject? {
        return client.getJson(path = "${mediaType.pathSegment()}/$tmdbId/external_ids")
    }

    suspend fun fetchSimilar(
        mediaType: MetadataLabMediaType,
        tmdbId: Int,
        language: String,
    ): JSONObject? {
        return client.getJson(
            path = "${mediaType.pathSegment()}/$tmdbId/similar",
            query = mapOf(
                "language" to language,
                "page" to "1",
            ),
        )
    }

    suspend fun fetchCollection(
        collectionId: Int,
        language: String,
    ): JSONObject? {
        return client.getJson(
            path = "collection/$collectionId",
            query = mapOf("language" to language),
        )
    }

    suspend fun fetchSeasonEpisodes(
        tmdbId: Int,
        seasonNumber: Int,
        language: String,
    ): JSONObject? {
        return client.getJson(
            path = "tv/$tmdbId/season/$seasonNumber",
            query = mapOf("language" to language),
        )
    }

    private fun enrichmentAppendToResponse(mediaType: MetadataLabMediaType): String {
        return if (mediaType == MetadataLabMediaType.MOVIE) {
            "credits,videos,reviews,images,release_dates"
        } else {
            "credits,videos,reviews,external_ids,images,content_ratings"
        }
    }

    private fun buildIncludeImageLanguage(language: String): String {
        val base = language.substringBefore('-').trim().ifBlank { "en" }
        return listOf(base, "en", "null").distinct().joinToString(",")
    }
}

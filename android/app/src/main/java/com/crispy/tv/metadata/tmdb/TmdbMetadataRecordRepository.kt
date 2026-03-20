package com.crispy.tv.metadata.tmdb

import com.crispy.tv.domain.metadata.MetadataRecord
import com.crispy.tv.player.MetadataLabMediaType
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

internal data class TmdbMetadataRecordResult(
    val title: String,
    val record: MetadataRecord,
)

internal class TmdbMetadataRecordRepository(
    private val remoteDataSource: TmdbTitleRemoteDataSource,
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
        val details = remoteDataSource.fetchMetadataDetails(resolvedMediaType, tmdbId, language) ?: return null

        val (credits, recommendations, externalIds) =
            coroutineScope {
                val creditsDeferred = async { remoteDataSource.fetchCredits(resolvedMediaType, tmdbId, language) }
                val recommendationsDeferred = async { remoteDataSource.fetchRecommendations(resolvedMediaType, tmdbId, language) }
                val externalIdsDeferred =
                    if (resolvedMediaType == MetadataLabMediaType.SERIES) {
                        async { remoteDataSource.fetchExternalIds(resolvedMediaType, tmdbId) }
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

        val castPairs = parseMetadataCastPairs(credits)
        val castNames = castPairs.map { it.first }
        val castWithDetails = castPairs.mapNotNull { (name, character) -> if (character != null) "$name as $character" else name }
        val directors =
            if (resolvedMediaType == MetadataLabMediaType.MOVIE) {
                parseMovieDirectors(credits)
            } else {
                parseSeriesCreators(details)
            }
        val similar = parseRecommendationIds(recommendations)
        val collectionItems =
            if (resolvedMediaType == MetadataLabMediaType.MOVIE) {
                parseMovieCollectionIds(details)
            } else {
                emptyList()
            }
        val seasons =
            if (resolvedMediaType == MetadataLabMediaType.SERIES) {
                parseMetadataSeasons(details, tmdbId)
            } else {
                emptyList()
            }

        val title = resolveTitle(details, resolvedMediaType) ?: "tmdb:$tmdbId"

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
}

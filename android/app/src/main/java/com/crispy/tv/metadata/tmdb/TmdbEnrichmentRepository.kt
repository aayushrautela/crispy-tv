package com.crispy.tv.metadata.tmdb

import com.crispy.tv.domain.metadata.normalizeNuvioMediaId
import com.crispy.tv.home.MediaDetails
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.network.CrispyHttpClient
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class TmdbEnrichmentResult(
    val enrichment: TmdbEnrichment,
    val fallbackDetails: MediaDetails,
)

class TmdbEnrichmentRepository internal constructor(
    client: TmdbJsonClient,
    private val identityService: TmdbIdentityService = TmdbIdentityService(client),
    private val remoteDataSource: TmdbTitleRemoteDataSource = TmdbTitleRemoteDataSource(client),
) {
    constructor(apiKey: String, httpClient: CrispyHttpClient) : this(
        TmdbJsonClient(apiKey = apiKey, httpClient = httpClient),
    )

    suspend fun load(
        rawId: String,
        mediaTypeHint: MetadataLabMediaType? = null,
        locale: Locale = Locale.getDefault(),
    ): TmdbEnrichmentResult? {
        val normalizedContentId = normalizeNuvioMediaId(rawId).contentId
        val imdbId = extractImdbId(normalizedContentId)
        val language = locale.toTmdbLanguageTag()
        val resolved = identityService.resolveTmdb(normalizedContentId, mediaTypeHint, language) ?: return null
        val detailsJson = remoteDataSource.fetchEnrichmentDetails(resolved.mediaType, resolved.tmdbId, language) ?: return null

        val credits = detailsJson.optJSONObject("credits")
        val videos = detailsJson.optJSONObject("videos")
        val reviews = detailsJson.optJSONObject("reviews")
        val collectionId =
            if (resolved.mediaType == MetadataLabMediaType.MOVIE) {
                detailsJson.optJSONObject("belongs_to_collection")?.optInt("id")?.takeIf { it > 0 }
            } else {
                null
            }

        val resolvedImdbId =
            when (resolved.mediaType) {
                MetadataLabMediaType.MOVIE -> detailsJson.optStringNonBlank("imdb_id")
                MetadataLabMediaType.SERIES -> detailsJson.optJSONObject("external_ids")?.optStringNonBlank("imdb_id")
                MetadataLabMediaType.ANIME -> detailsJson.optJSONObject("external_ids")?.optStringNonBlank("imdb_id")
            }?.let(::extractImdbId)

        val cast = parseCastMembers(credits)
        val backdropUrls = parseBackdropUrls(detailsJson.optJSONObject("images"), language)
        val production = parseProductionEntities(detailsJson, resolved.mediaType)
        val trailers = parseTrailers(videos)
        val parsedReviews = parseReviews(reviews)

        val (similar, collection) =
            coroutineScope {
                val similarDeferred = async { remoteDataSource.fetchSimilar(resolved.mediaType, resolved.tmdbId, language) }
                val collectionDeferred =
                    collectionId?.let { id ->
                        async { remoteDataSource.fetchCollection(id, language) }
                    }

                Pair(
                    parseSimilarCatalogItems(similarDeferred.await(), resolved.mediaType),
                    parseCollection(collectionDeferred?.await()),
                )
            }

        val enrichment =
            TmdbEnrichment(
                tmdbId = resolved.tmdbId,
                imdbId = resolvedImdbId ?: imdbId,
                mediaType = resolved.mediaType,
                backdropUrls = backdropUrls,
                cast = cast,
                production = production,
                trailers = trailers,
                reviews = parsedReviews,
                similar = similar,
                collection = collection,
                titleDetails = parseTitleDetails(detailsJson, resolved.mediaType),
            )

        val fallbackDetails =
            detailsJson.toFallbackMediaDetails(
                normalizedContentId = normalizedContentId,
                mediaType = resolved.mediaType,
                imdbId = resolvedImdbId ?: imdbId,
                preferredLanguage = language,
            )

        return TmdbEnrichmentResult(
            enrichment = enrichment,
            fallbackDetails = fallbackDetails,
        )
    }

    suspend fun loadArtwork(
        rawId: String,
        mediaTypeHint: MetadataLabMediaType? = null,
        locale: Locale = Locale.getDefault(),
    ): MediaDetails? {
        val normalizedContentId = normalizeNuvioMediaId(rawId).contentId
        val imdbId = extractImdbId(normalizedContentId)
        val language = locale.toTmdbLanguageTag()
        val resolved = identityService.resolveTmdb(normalizedContentId, mediaTypeHint, language) ?: return null
        val detailsJson = remoteDataSource.fetchArtworkDetails(resolved.mediaType, resolved.tmdbId, language) ?: return null

        val resolvedImdbId =
            when (resolved.mediaType) {
                MetadataLabMediaType.MOVIE -> detailsJson.optStringNonBlank("imdb_id")
                MetadataLabMediaType.SERIES -> detailsJson.optJSONObject("external_ids")?.optStringNonBlank("imdb_id")
                MetadataLabMediaType.ANIME -> detailsJson.optJSONObject("external_ids")?.optStringNonBlank("imdb_id")
            }?.let(::extractImdbId)

        return detailsJson.toFallbackMediaDetails(
            normalizedContentId = normalizedContentId,
            mediaType = resolved.mediaType,
            imdbId = resolvedImdbId ?: imdbId,
            preferredLanguage = language,
        )
    }

    suspend fun loadSeasonEpisodes(
        tmdbId: Int,
        seasonNumber: Int,
        locale: Locale = Locale.getDefault(),
    ): List<TmdbSeasonEpisode> {
        val seasonJson = remoteDataSource.fetchSeasonEpisodes(tmdbId, seasonNumber, locale.toTmdbLanguageTag()) ?: return emptyList()
        return parseSeasonEpisodes(seasonJson, seasonNumber)
    }
}

package com.crispy.tv.metadata.tmdb

import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.player.MetadataLabMediaType

data class TmdbCastMember(
    val id: Int,
    val name: String,
    val character: String?,
    val profileUrl: String?
)

data class TmdbProductionEntity(
    val id: Int,
    val name: String,
    val logoUrl: String?
)

data class TmdbTrailer(
    val id: String,
    val name: String,
    val site: String,
    val key: String,
    val type: String,
    val official: Boolean,
    val thumbnailUrl: String?,
    val watchUrl: String?
)

sealed interface TmdbTitleDetails {
    val status: String?
    val originalLanguage: String?
    val originCountries: List<String>
    val tagline: String?
}

data class TmdbMovieDetails(
    override val status: String?,
    val releaseDate: String?,
    val runtimeMinutes: Int?,
    val budget: Long?,
    val revenue: Long?,
    override val originalLanguage: String?,
    override val originCountries: List<String>,
    override val tagline: String?
) : TmdbTitleDetails

data class TmdbTvDetails(
    override val status: String?,
    val firstAirDate: String?,
    val lastAirDate: String?,
    val numberOfSeasons: Int?,
    val numberOfEpisodes: Int?,
    val episodeRunTimeMinutes: List<Int>,
    val type: String?,
    override val originalLanguage: String?,
    override val originCountries: List<String>,
    override val tagline: String?
) : TmdbTitleDetails

data class TmdbCollection(
    val id: Int,
    val name: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val parts: List<CatalogItem>
)

data class TmdbEnrichment(
    val tmdbId: Int,
    val imdbId: String?,
    val mediaType: MetadataLabMediaType,
    val cast: List<TmdbCastMember>,
    val production: List<TmdbProductionEntity>,
    val trailers: List<TmdbTrailer>,
    val similar: List<CatalogItem>,
    val collection: TmdbCollection?,
    val titleDetails: TmdbTitleDetails?
)

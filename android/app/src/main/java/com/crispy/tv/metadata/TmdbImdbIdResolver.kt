package com.crispy.tv.metadata

import com.crispy.tv.metadata.tmdb.TmdbIdentityService
import com.crispy.tv.metadata.tmdb.TmdbJsonClient
import com.crispy.tv.metadata.tmdb.TmdbResolvedId
import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.player.MetadataLabMediaType

/**
 * Resolved TMDB identity for a given IMDb ID.
 */
data class TmdbIdResult(
    val tmdbId: Int,
    val mediaType: MetadataLabMediaType,
)

class TmdbImdbIdResolver(
    private val identityService: TmdbIdentityService,
) {
    constructor(apiKey: String, httpClient: CrispyHttpClient) : this(
        TmdbIdentityService(TmdbJsonClient(apiKey = apiKey, httpClient = httpClient)),
    )

    suspend fun resolveImdbId(
        contentId: String,
        mediaType: MetadataLabMediaType,
    ): String? {
        return identityService.resolveImdbId(contentId = contentId, mediaType = mediaType)
    }

    suspend fun resolveTmdbFromImdb(imdbId: String): TmdbIdResult? {
        return identityService.resolveTmdbFromImdb(imdbId)?.toPublicResult()
    }
}

private fun TmdbResolvedId.toPublicResult(): TmdbIdResult {
    return TmdbIdResult(tmdbId = tmdbId, mediaType = mediaType)
}

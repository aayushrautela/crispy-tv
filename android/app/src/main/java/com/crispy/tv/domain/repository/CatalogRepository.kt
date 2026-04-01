package com.crispy.tv.domain.repository

import com.crispy.tv.backend.CrispyBackendClient

interface CatalogRepository {
    suspend fun resolveTitle(
        accessToken: String,
        provider: String,
        providerId: String,
        mediaType: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        absoluteEpisodeNumber: Int? = null,
    ): CrispyBackendClient.MetadataResolveResponse

    suspend fun getTitleDetail(
        accessToken: String,
        id: String,
    ): CrispyBackendClient.MetadataTitleDetailResponse

    suspend fun getTitleContent(
        accessToken: String,
        id: String,
    ): CrispyBackendClient.MetadataTitleContentResponse

    suspend fun listEpisodes(
        accessToken: String,
        id: String,
        seasonNumber: Int,
    ): CrispyBackendClient.MetadataEpisodeListResponse
}

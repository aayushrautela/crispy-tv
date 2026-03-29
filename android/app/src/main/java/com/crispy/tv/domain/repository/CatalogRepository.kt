package com.crispy.tv.domain.repository

import com.crispy.tv.backend.CrispyBackendClient

interface CatalogRepository {
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

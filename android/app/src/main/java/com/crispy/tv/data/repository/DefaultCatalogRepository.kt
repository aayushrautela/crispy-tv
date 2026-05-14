package com.crispy.tv.data.repository

import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.domain.repository.CatalogRepository

class DefaultCatalogRepository(
    private val backendClient: CrispyBackendClient,
) : CatalogRepository {
    override suspend fun getTitleDetail(
        accessToken: String,
        mediaKey: String,
    ): CrispyBackendClient.MetadataTitleDetailResponse {
        return backendClient.getMetadataTitleDetail(accessToken = accessToken, mediaKey = mediaKey)
    }

    override suspend fun getTitleExtras(
        accessToken: String,
        mediaKey: String,
    ): CrispyBackendClient.MetadataTitleExtrasResponse {
        return backendClient.getMetadataTitleExtras(accessToken = accessToken, mediaKey = mediaKey)
    }

    override suspend fun getTitleRatings(
        accessToken: String,
        profileId: String,
        mediaKey: String,
    ): CrispyBackendClient.MetadataTitleRatingsResponse {
        return backendClient.getMetadataTitleRatings(
            accessToken = accessToken,
            profileId = profileId,
            mediaKey = mediaKey,
        )
    }
}

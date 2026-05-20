package com.crispy.tv.data.repository

import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.domain.repository.CatalogRepository

class DefaultCatalogRepository(
    private val backendClient: CrispyBackendClient,
) : CatalogRepository {
    override suspend fun getTitleDetail(
        accessToken: String,
        itemId: String,
    ): CrispyBackendClient.MetadataTitleDetailResponse {
        return backendClient.getMetadataItemDetail(accessToken = accessToken, itemId = itemId)
    }

    override suspend fun getTitleExtras(
        accessToken: String,
        itemId: String,
    ): CrispyBackendClient.MetadataTitleExtrasResponse {
        return backendClient.getMetadataItemExtras(accessToken = accessToken, itemId = itemId)
    }

    override suspend fun getTitleRatings(
        accessToken: String,
        profileId: String,
        itemId: String,
    ): CrispyBackendClient.MetadataTitleRatingsResponse {
        return backendClient.getMetadataItemRatings(
            accessToken = accessToken,
            profileId = profileId,
            itemId = itemId,
        )
    }
}

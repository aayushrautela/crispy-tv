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

    override suspend fun getTitleReviews(
        accessToken: String,
        profileId: String,
        mediaKey: String,
    ): CrispyBackendClient.MetadataTitleReviewsResponse {
        return backendClient.getMetadataTitleReviews(
            accessToken = accessToken,
            profileId = profileId,
            mediaKey = mediaKey,
        )
    }

    override suspend fun getTitleContent(
        accessToken: String,
        mediaKey: String,
    ): CrispyBackendClient.MetadataTitleContentResponse {
        return backendClient.getMetadataTitleContent(accessToken = accessToken, mediaKey = mediaKey)
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

    override suspend fun listEpisodes(
        accessToken: String,
        mediaKey: String,
        seasonNumber: Int,
    ): CrispyBackendClient.MetadataEpisodeListResponse {
        return backendClient.listMetadataEpisodes(
            accessToken = accessToken,
            mediaKey = mediaKey,
            seasonNumber = seasonNumber,
        )
    }
}

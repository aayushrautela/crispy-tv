package com.crispy.tv.domain.repository

import com.crispy.tv.backend.CrispyBackendClient

interface CatalogRepository {
    suspend fun getTitleDetail(
        accessToken: String,
        mediaKey: String,
    ): CrispyBackendClient.MetadataTitleDetailResponse

    suspend fun getTitleReviews(
        accessToken: String,
        profileId: String,
        mediaKey: String,
    ): CrispyBackendClient.MetadataTitleReviewsResponse

    suspend fun getTitleContent(
        accessToken: String,
        mediaKey: String,
    ): CrispyBackendClient.MetadataTitleContentResponse

    suspend fun getTitleRatings(
        accessToken: String,
        profileId: String,
        mediaKey: String,
    ): CrispyBackendClient.MetadataTitleRatingsResponse

    suspend fun listEpisodes(
        accessToken: String,
        mediaKey: String,
        seasonNumber: Int,
    ): CrispyBackendClient.MetadataEpisodeListResponse
}

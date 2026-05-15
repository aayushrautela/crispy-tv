package com.crispy.tv.domain.repository

import com.crispy.tv.backend.CrispyBackendClient

interface CatalogRepository {
    suspend fun getTitleDetail(
        accessToken: String,
        mediaKey: String,
    ): CrispyBackendClient.MetadataTitleDetailResponse

    suspend fun getTitleExtras(
        accessToken: String,
        mediaKey: String,
    ): CrispyBackendClient.MetadataTitleExtrasResponse

    suspend fun getTitleRatings(
        accessToken: String,
        profileId: String,
        mediaKey: String,
    ): CrispyBackendClient.MetadataTitleRatingsResponse
}

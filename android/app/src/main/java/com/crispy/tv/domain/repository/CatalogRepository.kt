package com.crispy.tv.domain.repository

import com.crispy.tv.backend.CrispyBackendClient

interface CatalogRepository {
    suspend fun getTitleDetail(
        accessToken: String,
        itemId: String,
    ): CrispyBackendClient.MetadataTitleDetailResponse

    suspend fun getTitleExtras(
        accessToken: String,
        itemId: String,
    ): CrispyBackendClient.MetadataTitleExtrasResponse

    suspend fun getTitleRatings(
        accessToken: String,
        profileId: String,
        itemId: String,
    ): CrispyBackendClient.MetadataTitleRatingsResponse
}

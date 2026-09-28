package com.crispy.tv.data.repository

import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.domain.repository.CatalogRepository
import com.crispy.tv.backend.MetadataSeriesEpisodesResponse
import com.crispy.tv.backend.MetadataTitleDetailResponse
import com.crispy.tv.backend.MetadataTitleExtrasResponse
import com.crispy.tv.backend.MetadataTitleRatingsResponse

class DefaultCatalogRepository(
    private val backendClient: CrispyBackendClient,
) : CatalogRepository {
    override suspend fun getTitleDetail(
        accessToken: String,
        itemId: String,
    ): MetadataTitleDetailResponse {
        return backendClient.getMetadataItemDetail(accessToken = accessToken, itemId = itemId)
    }

    override suspend fun getTitleExtras(
        accessToken: String,
        itemId: String,
    ): MetadataTitleExtrasResponse {
        return backendClient.getMetadataItemExtras(accessToken = accessToken, itemId = itemId)
    }

    override suspend fun getSeriesEpisodes(
        accessToken: String,
        seriesItemId: String,
        season: Int?,
    ): MetadataSeriesEpisodesResponse {
        return backendClient.getSeriesEpisodes(
            accessToken = accessToken,
            seriesItemId = seriesItemId,
            season = season,
        )
    }

    override suspend fun getTitleRatings(
        accessToken: String,
        profileId: String,
        itemId: String,
    ): MetadataTitleRatingsResponse {
        return backendClient.getMetadataItemRatings(
            accessToken = accessToken,
            profileId = profileId,
            itemId = itemId,
        )
    }
}

package com.crispy.tv.domain.repository

import com.crispy.tv.backend.MetadataSeriesEpisodesResponse
import com.crispy.tv.backend.MetadataTitleDetailResponse
import com.crispy.tv.backend.MetadataTitleExtrasResponse
import com.crispy.tv.backend.MetadataTitleRatingsResponse

interface CatalogRepository {
    suspend fun getTitleDetail(
        accessToken: String,
        itemId: String,
    ): MetadataTitleDetailResponse

    suspend fun getTitleExtras(
        accessToken: String,
        itemId: String,
    ): MetadataTitleExtrasResponse

    suspend fun getSeriesEpisodes(
        accessToken: String,
        seriesItemId: String,
        season: Int? = null,
    ): MetadataSeriesEpisodesResponse

    suspend fun getTitleRatings(
        accessToken: String,
        profileId: String,
        itemId: String,
    ): MetadataTitleRatingsResponse
}

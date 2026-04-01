package com.crispy.tv.data.repository

import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.domain.repository.CatalogRepository

class DefaultCatalogRepository(
    private val backendClient: CrispyBackendClient,
) : CatalogRepository {
    override suspend fun resolveTitle(
        accessToken: String,
        provider: String,
        providerId: String,
        mediaType: String,
        seasonNumber: Int?,
        episodeNumber: Int?,
        absoluteEpisodeNumber: Int?,
    ): CrispyBackendClient.MetadataResolveResponse {
        return backendClient.resolveMetadata(
            accessToken = accessToken,
            input = CrispyBackendClient.MediaLookupInput(
                provider = provider,
                providerId = providerId,
                mediaType = mediaType,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                absoluteEpisodeNumber = absoluteEpisodeNumber,
            ),
        )
    }

    override suspend fun getTitleDetail(
        accessToken: String,
        id: String,
    ): CrispyBackendClient.MetadataTitleDetailResponse {
        return backendClient.getMetadataTitleDetail(accessToken = accessToken, id = id)
    }

    override suspend fun getTitleContent(
        accessToken: String,
        id: String,
    ): CrispyBackendClient.MetadataTitleContentResponse {
        return backendClient.getMetadataTitleContent(accessToken = accessToken, id = id)
    }

    override suspend fun listEpisodes(
        accessToken: String,
        id: String,
        seasonNumber: Int,
    ): CrispyBackendClient.MetadataEpisodeListResponse {
        return backendClient.listMetadataEpisodes(
            accessToken = accessToken,
            id = id,
            seasonNumber = seasonNumber,
        )
    }
}

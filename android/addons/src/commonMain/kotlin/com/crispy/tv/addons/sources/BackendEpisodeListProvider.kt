package com.crispy.tv.addons.sources

import com.crispy.tv.accounts.AccountApi
import com.crispy.tv.backend.BackendApi
import com.crispy.tv.domain.watch.EpisodeInfo
import com.crispy.tv.player.EpisodeListProvider

class BackendEpisodeListProvider(
    private val supabaseAccountClient: AccountApi,
    private val backendClient: BackendApi,
) : EpisodeListProvider {
    override suspend fun fetchEpisodeList(
        mediaType: String,
        contentId: String,
        seasonHint: Int?,
    ): List<EpisodeInfo>? {
        val normalizedMediaType = mediaType.trim()
        if (
            !normalizedMediaType.equals("show", ignoreCase = true) &&
                !normalizedMediaType.equals("anime", ignoreCase = true)
        ) {
            return null
        }

        val itemId = contentId.trim()
        if (itemId.isBlank()) return null

        val session = supabaseAccountClient.ensureValidSession() ?: return null

        val response = runCatching {
            backendClient.getSeriesEpisodes(
                accessToken = session.accessToken,
                seriesItemId = itemId,
                season = seasonHint,
            )
        }.getOrNull() ?: return null

        return response.items
            .asSequence()
            .mapNotNull { episode ->
                val season = episode.parent?.seasonNumber ?: return@mapNotNull null
                val number = episode.parent?.episodeNumber ?: return@mapNotNull null
                if (season <= 0 || number <= 0) return@mapNotNull null
                EpisodeInfo(
                    season = season,
                    episode = number,
                    title = episode.title,
                    released = episode.releaseDate,
                )
            }.toList().takeIf { it.isNotEmpty() }
    }
}

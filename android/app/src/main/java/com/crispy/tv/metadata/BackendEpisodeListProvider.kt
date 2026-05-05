package com.crispy.tv.metadata

import com.crispy.tv.accounts.SupabaseAccountClient
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.domain.watch.EpisodeInfo
import com.crispy.tv.player.EpisodeListProvider

internal class BackendEpisodeListProvider(
    private val supabaseAccountClient: SupabaseAccountClient,
    private val backendClient: CrispyBackendClient,
) : EpisodeListProvider {
    override suspend fun fetchEpisodeList(
        mediaType: String,
        contentId: String,
        seasonHint: Int?,
    ): List<EpisodeInfo>? {
        val normalizedMediaType = mediaType.trim()
        if (
            !normalizedMediaType.equals("series", ignoreCase = true) &&
                !normalizedMediaType.equals("anime", ignoreCase = true)
        ) {
            return null
        }

        val mediaKey = contentId.trim()
        if (mediaKey.isBlank()) return null

        val session = supabaseAccountClient.ensureValidSession() ?: return null

        val response = runCatching {
            backendClient.getMetadataTitleDetail(
                accessToken = session.accessToken,
                mediaKey = mediaKey,
            )
        }.getOrNull() ?: return null

        return response.episodes
            .asSequence()
            .filter { episode -> seasonHint == null || episode.seasonNumber == seasonHint }
            .mapNotNull { episode ->
                val season = episode.seasonNumber ?: return@mapNotNull null
                val number = episode.episodeNumber ?: return@mapNotNull null
                if (season <= 0 || number <= 0) return@mapNotNull null
                EpisodeInfo(
                    season = season,
                    episode = number,
                    title = episode.title,
                    released = episode.airDate,
                )
            }.toList().takeIf { it.isNotEmpty() }
    }
}

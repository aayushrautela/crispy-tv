package com.crispy.tv.watchhistory

import com.crispy.tv.home.MediaDetails
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.WatchProvider
import com.crispy.tv.player.WatchProviderAuthState
import java.util.Locale

fun preferredWatchProvider(authState: WatchProviderAuthState): WatchProvider? {
    return when {
        authState.traktAuthenticated -> WatchProvider.TRAKT
        authState.simklAuthenticated -> WatchProvider.SIMKL
        else -> null
    }
}

fun matchesMediaType(expected: MetadataLabMediaType?, actual: MetadataLabMediaType): Boolean {
    return expected == null || expected == actual
}

fun matchesContentId(candidate: String, targetNormalizedId: String): Boolean {
    return candidate.trim().lowercase(Locale.US) == targetNormalizedId
}

fun episodeWatchKeyCandidates(
    details: MediaDetails,
    season: Int,
    episode: Int,
): Set<String> {
    return buildSet {
        addEpisodeKey(details.id, season, episode)?.let(::add)
    }
}

fun addEpisodeKey(
    contentId: String,
    season: Int,
    episode: Int,
): String? {
    val normalized = contentId.trim().takeIf { it.isNotBlank() } ?: return null
    return "${normalized.lowercase(Locale.US)}:$season:$episode"
}

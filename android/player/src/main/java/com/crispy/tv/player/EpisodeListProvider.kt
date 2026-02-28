package com.crispy.tv.player

import com.crispy.tv.domain.watch.EpisodeInfo

/**
 * Provides episode lists for series.
 *
 * This interface lives in the player module so both `watchhistory`
 * and `app` modules can depend on it:
 * - `watchhistory` calls it during CW flow to find next episodes
 * - `app` implements it using a metadata backend (TMDB)
 *
 * Returns a list of (season, episode) pairs, optionally enriched with
 * a title and air date.
 */
fun interface EpisodeListProvider {
    /**
     * Fetch the episode list for a piece of content.
     *
     * @param mediaType Media type: `"series"` or `"movie"`
     * @param contentId Canonical content ID (e.g. `"tt1234567"` or `"tmdb:123"`)
     * @param seasonHint Optional hint for the season the caller cares about.
     * @return list of episodes, or `null` if metadata couldn't be fetched
     */
    suspend fun fetchEpisodeList(mediaType: String, contentId: String, seasonHint: Int? = null): List<EpisodeInfo>?
}

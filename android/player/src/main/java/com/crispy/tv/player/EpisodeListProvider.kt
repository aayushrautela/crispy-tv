package com.crispy.tv.player

import com.crispy.tv.domain.watch.AddonEpisodeInfo

/**
 * Provides episode lists from addon metadata (e.g. Cinemeta).
 *
 * This interface lives in the player module so both `watchhistory`
 * and `app` modules can depend on it:
 * - `watchhistory` calls it during CW flow to find next episodes
 * - `app` implements it using addon/HTTP infrastructure
 *
 * Mirrors Nuvio's `getCachedMetadata(type, id)` → `metadata.videos`,
 * but only returns the episode list (poster/title enrichment is
 * handled separately by HomeCatalogService).
 */
fun interface EpisodeListProvider {
    /**
     * Fetch the episode list for a piece of content from addon metadata.
     *
     * @param mediaType Stremio media type: `"series"` or `"movie"`
     * @param contentId IMDb ID (e.g. `"tt1234567"`) or other addon-supported ID
     * @return list of episodes from the addon's `videos` array, or `null` if
     *         metadata couldn't be fetched or the content has no episodes
     */
    suspend fun fetchEpisodeList(mediaType: String, contentId: String): List<AddonEpisodeInfo>?
}

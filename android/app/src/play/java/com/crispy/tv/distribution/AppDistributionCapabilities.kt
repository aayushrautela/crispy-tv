package com.crispy.tv.distribution

import com.crispy.tv.platform.DistributionCapabilities

/**
 * Store build: neither the QuickJS plugin runtime nor the torrent engine ships,
 * so every optional capability is off.
 */
internal object AppDistributionCapabilities : DistributionCapabilities {
    override val pluginsUiSupported: Boolean = false
    override val pluginsRuntimeAvailable: Boolean = false
    override val youtubeInHeroPlaybackSupported: Boolean = false
    override val torrentPlaybackSupported: Boolean = false
}

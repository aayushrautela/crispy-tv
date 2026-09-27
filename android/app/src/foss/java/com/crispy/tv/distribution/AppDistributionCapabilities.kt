package com.crispy.tv.distribution

import com.crispy.tv.platform.DistributionCapabilities

/**
 * Sideload build: the QuickJS plugin runtime and the torrent engine both ship,
 * so every optional capability is on.
 */
internal object AppDistributionCapabilities : DistributionCapabilities {
    override val pluginsUiSupported: Boolean = true
    override val pluginsRuntimeAvailable: Boolean = true
    override val youtubeInHeroPlaybackSupported: Boolean = true
    override val torrentPlaybackSupported: Boolean = true
}

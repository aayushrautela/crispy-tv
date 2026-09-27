package com.crispy.tv.distribution

import com.crispy.tv.platform.DistributionCapabilities

/**
 * FOSS build: the QuickJS plugin runtime ships, so every plugin capability is on.
 */
internal object AppDistributionCapabilities : DistributionCapabilities {
    override val pluginsUiSupported: Boolean = true
    override val pluginsRuntimeAvailable: Boolean = true
    override val youtubeInHeroPlaybackSupported: Boolean = true
}

package com.crispy.tv.distribution

import com.crispy.tv.platform.DistributionCapabilities

/**
 * Play build: the QuickJS plugin runtime does not ship, so every plugin
 * capability is off.
 */
internal object AppDistributionCapabilities : DistributionCapabilities {
    override val pluginsUiSupported: Boolean = false
    override val pluginsRuntimeAvailable: Boolean = false
    override val youtubeInHeroPlaybackSupported: Boolean = false
}

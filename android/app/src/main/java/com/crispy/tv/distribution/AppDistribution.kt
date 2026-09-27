package com.crispy.tv.distribution

import com.crispy.tv.platform.DistributionCapabilities

/**
 * Single read point for the build's capabilities.
 *
 * The per-flavor source sets each supply one [AppDistributionCapabilities]
 * object, so this is the only place that has to know a flavor exists. Call
 * sites read a named capability and never branch on the build variant.
 */
internal object AppDistribution {
    val capabilities: DistributionCapabilities = AppDistributionCapabilities
}

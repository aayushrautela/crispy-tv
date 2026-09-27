package com.crispy.tv.platform

/**
 * Replaces the per-flavor `src/play` and `src/foss` source sets in `:app`.
 *
 * All three capabilities currently track the same axis — they are all `true` on
 * `foss` and `false` on `play` — but they are modelled separately so a future
 * distribution can enable one without the others, and so no build-time flavor
 * dimension is needed to read them.
 *
 * Current values, for audit against the source sets this replaces:
 *
 * | capability                        | play | foss |
 * |-----------------------------------|------|------|
 * | [pluginsUiSupported]              | false| true |
 * | [pluginsRuntimeAvailable]         | false| true |
 * | [youtubeInHeroPlaybackSupported]  | false| true |
 *
 * [pluginsRuntimeAvailable] subsumes both former `null`-returning factories:
 * `PluginStreamLoaderProvider.get` and `createPluginSyncBridge`.
 */
interface DistributionCapabilities {
    /** Whether the plugins settings screen is reachable. */
    val pluginsUiSupported: Boolean

    /** Whether the QuickJS plugin runtime can be constructed at all. */
    val pluginsRuntimeAvailable: Boolean

    /** Whether YouTube trailers may play inline in the details hero. */
    val youtubeInHeroPlaybackSupported: Boolean
}

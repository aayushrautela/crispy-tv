package com.crispy.tv.platform

/**
 * Replaces the per-flavor `src/store` and `src/sideload` source sets in `:app`.
 *
 * All four capabilities currently track the same axis — they are all `true` on
 * `sideload` and `false` on `store` — but they are modelled separately so a future
 * distribution can enable one without the others, and so no build-time flavor
 * dimension is needed to read them.
 *
 * Current values, for audit against the source sets this replaces:
 *
 * | capability                        | store | sideload |
 * |-----------------------------------|-------|----------|
 * | [pluginsUiSupported]              | false | true     |
 * | [pluginsRuntimeAvailable]         | false | true     |
 * | [youtubeInHeroPlaybackSupported]  | false | true     |
 * | [torrentPlaybackSupported]        | false | true     |
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

    /**
     * Whether the torrent engine ships in this build.
     *
     * False on store builds: the engine is a separate module that only the
     * sideload flavor depends on, so this is a build-time fact rather than a
     * runtime check.
     */
    val torrentPlaybackSupported: Boolean
}

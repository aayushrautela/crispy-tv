package com.crispy.tv.distribution

import android.content.Context
import androidx.compose.runtime.Composable
import com.crispy.tv.PlaybackDependencies
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.details.trailer.TrailerExtractor
import com.crispy.tv.details.trailer.TrailerPlaybackSource
import com.crispy.tv.platform.DistributionCapabilities
import com.crispy.tv.streams.PluginStreamLoader
import com.crispy.tv.sync.PluginAddonsSyncBridge

/**
 * Store variant: no optional engines ship, so every capability is off and every
 * plugin entry point is absent.
 *
 * The absences are structural, not runtime checks. `:android:plugins` and
 * `:android:torrent-engine` are separate modules that only the sideload variant
 * depends on, so their classes are not on this APK's classpath at all. That is
 * the guarantee `verifyDistributionExclusions` asserts by reading the resolved
 * dependency graph, and `verify_apk_distribution.py` asserts again by reading
 * the dex.
 */
internal object BuildDistributionComponents : DistributionComponents {

    override val capabilities: DistributionCapabilities = object : DistributionCapabilities {
        override val pluginsUiSupported: Boolean = false
        override val pluginsRuntimeAvailable: Boolean = false
        override val youtubeInHeroPlaybackSupported: Boolean = false
        override val torrentPlaybackSupported: Boolean = false
    }

    /**
     * `null` from [resolve], which is a real answer rather than a stub:
     * `:android:youtube-extractor` is not on this APK's classpath, so there is
     * nothing to call. `DetailsHero` reads the null and falls back to the
     * direct-file trailer source (IMDb), or to the embedded-player path for a
     * YouTube link.
     */
    override val trailerExtractor: TrailerExtractor = object : TrailerExtractor {
        override fun resolve(
            videoId: String,
            viewportWidthPx: Int,
            viewportHeightPx: Int,
        ): TrailerPlaybackSource? = null
    }

    /**
     * Null rather than a no-op loader: a loader that always returns nothing
     * would still make the selector coordinator take its plugin code path, so
     * absence is the honest answer.
     */
    override fun pluginStreamLoader(context: Context): PluginStreamLoader? = null

    override fun pluginSyncBridge(
        context: Context,
        backend: CrispyBackendClient,
    ): PluginAddonsSyncBridge? = null

    /**
     * Nothing to install; `PlaybackDependencies` keeps its
     * `UnavailableTorrentResolver` default, which fails fast on use.
     */
    override fun installTorrentResolver(dependencies: PlaybackDependencies) = Unit

    /**
     * Null, so `SettingsNavGraph` does not register the destination at all.
     *
     * This is defence in depth rather than the only guard: `SettingsScreen`
     * already hides the plugins row on `pluginsUiSupported == false`, so the
     * old stub screen was already unreachable. Nulling it additionally means a
     * deep link to `settings/plugins` cannot land on a placeholder that claims
     * plugins are "only available in the sideload build" -- which would be a
     * confusing thing to read on a build that has no sideload counterpart.
     */
    override val pluginsSettingsScreen: (@Composable (onBack: () -> Unit) -> Unit)? = null
}

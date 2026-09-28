package com.crispy.tv.distribution

import android.content.Context
import androidx.compose.runtime.Composable
import com.crispy.tv.PlaybackDependencies
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.details.trailer.TrailerExtractor
import com.crispy.tv.network.AppHttp
import com.crispy.tv.platform.DistributionCapabilities
import com.crispy.tv.plugins.streams.PluginStreamsServiceFactory
import com.crispy.tv.settings.PluginsSettingsRoute
import com.crispy.tv.streams.PluginStreamLoader
import com.crispy.tv.sync.PluginAddonsSyncBridge
import com.crispy.tv.sync.SideloadPluginAddonsSyncBridge
import com.crispy.tv.torrentengine.NativeTorrentResolver
import com.crispy.tv.youtubetractor.YouTubeTrailerExtractor

/**
 * Sideload variant: the plugin runtime, the torrent engine and inline YouTube
 * trailers all ship.
 *
 * Every class referenced below lives in `:android:plugins`,
 * `:android:torrent-engine` or `:android:youtube-extractor`, which only this
 * variant depends on. That
 * structural exclusion -- not a runtime check -- is what keeps them out of store
 * builds, and it is what `verifyDistributionExclusions` and
 * `verify_apk_distribution.py` assert.
 */
internal object BuildDistributionComponents : DistributionComponents {

    override val capabilities: DistributionCapabilities = object : DistributionCapabilities {
        override val pluginsUiSupported: Boolean = true
        override val pluginsRuntimeAvailable: Boolean = true
        override val youtubeInHeroPlaybackSupported: Boolean = true
        override val torrentPlaybackSupported: Boolean = true
    }

    override val trailerExtractor: TrailerExtractor = YouTubeTrailerExtractor

    /**
     * The loader wraps the QuickJS-backed stream source.
     *
     * Constructing it is expensive (it spins up a JS runtime and an HTTP
     * client), so it is created once and held. The holder is a `val` on this
     * object rather than a separate `object PluginStreamLoaderProvider`, so the
     * lifetime is tied to the components' and cannot drift out of sync with it.
     */
    override fun pluginStreamLoader(context: Context): PluginStreamLoader? = cachedLoader
        ?: synchronized(this) {
            cachedLoader ?: create(context.applicationContext).also { cachedLoader = it }
        }

    @Volatile
    private var cachedLoader: PluginStreamLoader? = null

    private fun create(appContext: Context): PluginStreamLoader {
        val source = PluginStreamsServiceFactory.create(appContext, AppHttp.okHttp(appContext))
        return PluginStreamLoader { request ->
            source.stream(
                mediaType = request.mediaType,
                lookupId = request.lookupId,
                tmdbId = request.tmdbId,
                season = request.season,
                episode = request.episode,
            )
        }
    }

    override fun pluginSyncBridge(
        context: Context,
        backend: CrispyBackendClient,
    ): PluginAddonsSyncBridge = SideloadPluginAddonsSyncBridge.create(context, backend)

    override fun installTorrentResolver(dependencies: PlaybackDependencies) {
        dependencies.torrentResolverFactory = { context -> NativeTorrentResolver(context) }
    }

    override val pluginsSettingsScreen: (@Composable (onBack: () -> Unit) -> Unit)?
        get() = { onBack -> PluginsSettingsRoute(onBack = onBack) }
}

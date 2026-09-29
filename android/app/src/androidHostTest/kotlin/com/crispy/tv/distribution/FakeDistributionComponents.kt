package com.crispy.tv.distribution

import android.content.Context
import androidx.compose.runtime.Composable
import com.crispy.tv.PlaybackDependencies
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.details.trailer.TrailerExtractor
import com.crispy.tv.details.trailer.TrailerPlaybackSource
import com.crispy.tv.platform.DistributionCapabilities
import com.crispy.tv.player.TorrentResolver
import com.crispy.tv.streams.PluginStreamLoader
import com.crispy.tv.sync.PluginAddonsSyncBridge

/**
 * A [DistributionComponents] that counts its own activation and installs a
 * [RecordingTorrentResolver] the test can identify.
 *
 * The real implementations live in `:androidApp` under `src/store` and
 * `src/sideload`, which is downstream of this module, so they cannot be named
 * from here. That is not what is under test. What is under test is that
 * `AppDistribution` *activates* whatever it is handed, and the only way to see
 * that from outside is to hand it something recognizable. The per-variant
 * claims are asserted separately, in `:androidApp`'s `DistributionComponentsTest`.
 */
class FakeDistributionComponents : DistributionComponents {

    var installCalls: Int = 0
        private set

    val resolver = RecordingTorrentResolver()

    override val capabilities: DistributionCapabilities = object : DistributionCapabilities {
        override val pluginsUiSupported: Boolean = false
        override val pluginsRuntimeAvailable: Boolean = false
        override val youtubeInHeroPlaybackSupported: Boolean = false
        override val torrentPlaybackSupported: Boolean = true
    }

    override val trailerExtractor: TrailerExtractor = object : TrailerExtractor {
        override fun resolve(
            videoId: String,
            viewportWidthPx: Int,
            viewportHeightPx: Int,
        ): TrailerPlaybackSource? = null
    }

    override fun pluginStreamLoader(context: Context): PluginStreamLoader? = null

    override fun pluginSyncBridge(
        context: Context,
        backend: CrispyBackendClient,
    ): PluginAddonsSyncBridge? = null

    override fun installTorrentResolver(dependencies: PlaybackDependencies) {
        installCalls += 1
        dependencies.torrentResolverFactory = { resolver }
    }

    override val pluginsSettingsScreen: (@Composable (onBack: () -> Unit) -> Unit)? = null
}

/** A [TorrentResolver] identified by its own type, so a test can assert on it. */
class RecordingTorrentResolver : TorrentResolver {
    var calls: Int = 0
        private set

    override suspend fun resolveStreamUrl(magnetLink: String, sessionId: String): String {
        calls += 1
        return "resolved:$magnetLink"
    }

    override fun stopAndClear() = Unit

    override fun close() = Unit
}

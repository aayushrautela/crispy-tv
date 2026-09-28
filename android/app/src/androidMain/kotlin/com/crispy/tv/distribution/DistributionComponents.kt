package com.crispy.tv.distribution

import android.content.Context
import androidx.compose.runtime.Composable
import com.crispy.tv.PlaybackDependencies
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.details.trailer.TrailerExtractor
import com.crispy.tv.platform.DistributionCapabilities
import com.crispy.tv.streams.PluginStreamLoader
import com.crispy.tv.sync.PluginAddonsSyncBridge

/**
 * Everything the build variant supplies, in one place.
 *
 * ## Why this exists
 *
 * `:app` is becoming a Kotlin Multiplatform library, and the KMP library plugin
 * is single-variant — it cannot have `src/store` and `src/sideload` source
 * sets. The variant now lives in `:androidApp`, which keeps the
 * `productFlavors`. That inverts the dependency: `:androidApp` knows the
 * variant, `:app` must not.
 *
 * Five symbols used to be referenced directly from `:app`'s main source set and
 * were each defined in both flavour source sets:
 *
 * | symbol                        | referenced from main                       |
 * |-------------------------------|--------------------------------------------|
 * | `AppDistributionCapabilities` | `AppDistribution`                           |
 * | `PluginStreamLoaderProvider`  | 3 view-model factories                      |
 * | `createPluginSyncBridge`      | `PluginSyncBridgeProvider`                  |
 * | `PluginsSettingsRoute`        | `SettingsNavGraph`                          |
 * | `installTorrentResolver`      | `PlaybackDependencies`                      |
 *
 * They are gathered here behind one interface so `:app` has a single
 * distribution seam instead of five, and so the alternative is not five mutable
 * singletons. One implementation per variant, chosen once in `:androidApp`'s
 * `CrispyApplication` and installed through `AppDistribution`.
 *
 * A sixth member, [trailerExtractor], joined them when the Phase 1 split forced
 * `:android:network` to lose its flavour axis: the KMP library plugin is
 * single-variant, so `:app` could not resolve between `:android:network`'s
 * `store` and `sideload` variants. The same shape as the five above, reached for
 * the same reason.
 *
 * ## What the store implementation must do
 *
 * It returns `null` / no-ops throughout. The store build has no
 * `:android:plugins` and no `:android:torrent-engine` on its classpath at all —
 * they are separate modules only the sideload variant depends on — so the absence
 * is structural, not a runtime check. See `DistributionCapabilities` for the
 * four boolean flags and their audit table.
 */
interface DistributionComponents {

    /** Which optional engines this build ships. */
    val capabilities: DistributionCapabilities

    /**
     * Resolves YouTube trailer ids into playable stream URLs.
     *
     * The interface lives in `:android:network` because it is flavour-free; the
     * implementation that actually talks to NewPipeExtractor lives in
     * `:android:youtube-extractor`, which only the sideload variant depends on.
     * The store implementation returns `null`, which routes the caller to the
     * direct-file source (IMDb) or the embedded-player path.
     */
    val trailerExtractor: TrailerExtractor

    /**
     * A loader for plugin-provided streams, or `null` on a build with no plugin
     * runtime. Callers must tolerate `null`; the store implementation always is.
     */
    fun pluginStreamLoader(context: Context): PluginStreamLoader?

    /**
     * A bridge that syncs plugin-provided addons, or `null` on a build with no
     * plugin runtime.
     */
    fun pluginSyncBridge(context: Context, backend: CrispyBackendClient): PluginAddonsSyncBridge?

    /**
     * Installs the variant's torrent resolver onto [dependencies].
     *
     * The store implementation does nothing: `:android:torrent-engine` is not on
     * its classpath, so `PlaybackDependencies` keeps the
     * `UnavailableTorrentResolver` default that fails fast on use.
     */
    fun installTorrentResolver(dependencies: PlaybackDependencies)

    /**
     * The plugins settings screen, or `null` when the build has no plugins UI.
     *
     * Null rather than a stub screen, because a store build should not present a
     * plugins page at all -- navigation omits the destination instead of showing
     * an empty one.
     */
    val pluginsSettingsScreen: (@Composable (onBack: () -> Unit) -> Unit)?
}

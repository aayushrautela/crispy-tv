package com.crispy.tv.desktop

import com.crispy.tv.platform.AppLogger
import com.crispy.tv.platform.DistributionCapabilities
import com.crispy.tv.platform.KeyValueStore
import com.crispy.tv.platform.MonotonicClock
import com.crispy.tv.platform.SecretStore
import com.crispy.tv.platform.TimeSource
import com.crispy.tv.platform.desktop.DesktopAppLogger
import com.crispy.tv.platform.desktop.DesktopDistributionCapabilities
import com.crispy.tv.platform.desktop.DesktopMonotonicClock
import com.crispy.tv.platform.desktop.DesktopPaths
import com.crispy.tv.platform.desktop.DesktopSecretStore
import com.crispy.tv.platform.desktop.DesktopTimeSource
import com.crispy.tv.platform.desktop.FileKeyValueStore
import com.crispy.tv.settings.ImageSettingsRepository
import com.crispy.tv.settings.KeyValueStoreImageSettingsRepository
import java.io.File

/**
 * The desktop composition root: the six `:android:platform-core` interfaces,
 * implemented.
 *
 * ## Why this exists
 *
 * `platform-core` declares six ports, and this was the module that had no
 * implementation of any of them, which is why it could not inject a clock, a
 * logger or a settings store into anything portable. The implementations live in
 * `:android:platform-desktop`; this is where the desktop application *chooses*
 * them and where the two paths that do not exist on Android -- the application
 * data directory and the key file -- are decided.
 *
 * That split is the point. `:android:platform-desktop` answers "what does a
 * desktop `KeyValueStore` do", and this answers "which store, in which
 * directory, for this build". A packaged `.exe` and a development run want the
 * same store type and different locations, and that is a wiring question, not an
 * implementation one.
 *
 * ## Why the capabilities are constructed here
 *
 * `DesktopDistributionCapabilities` is a value whose four flags are all false,
 * and it is constructed explicitly anyway. A capability that appears by default
 * is a capability nobody decided on; writing it out at the composition root is
 * how a reader learns that desktop has no plugin runtime, no inline YouTube
 * trailer and no torrent engine -- and where to change that when one arrives.
 */
internal class DesktopEnvironment(
    dataDirectory: File = DesktopPaths.applicationDataDirectory(),
    debug: Boolean = System.getProperty("crispy.debug") != null,
) {
    val logger: AppLogger = DesktopAppLogger(debug = debug)

    /**
     * A monotonic clock is *constructed* here and shared, not a fresh instance at
     * each use.
     *
     * That is the one place the two clocks cannot be swapped by accident: a
     * `MonotonicClock` is an origin plus a reading, so a caller that constructed
     * its own would compare an elapsed time against a different origin and get a
     * large or negative interval. Sharing one instance is what makes two
     * readings comparable at all.
     */
    val monotonicClock: MonotonicClock = DesktopMonotonicClock()

    val timeSource: TimeSource = DesktopTimeSource()

    val capabilities: DistributionCapabilities = DesktopDistributionCapabilities()

    val settings: KeyValueStore = FileKeyValueStore(dataDirectory, SETTINGS_STORE_NAME)

    val tokens: SecretStore = DesktopSecretStore(File(dataDirectory, "secrets/token.key"))

    /**
     * `:android:app`'s own image-quality screen and repository, wired to a desktop
     * store.
     *
     * Neither half is duplicated here. `ImageSettingsScreen` is a public
     * `commonMain` composable and `KeyValueStoreImageSettingsRepository` was
     * widened from `internal` for exactly this call site -- the same rule
     * `ContinueWatchingRail` was widened under, and for the same reason: a
     * `commonMain` declaration that gains a caller outside its module has to be
     * public, and only the one that actually gained one is.
     *
     * The repository takes an `onQualityChanged` callback with **no default**,
     * because dropping the decoded-image cache is a Coil concern that cannot live
     * in a repository. On Android something passes a cache invalidator. Here the
     * desktop renders no artwork at all, so the honest callback is the empty one --
     * and because the parameter has no default, saying so is a decision written
     * down here rather than a runtime surprise on the first quality change.
     */
    val imageSettings: ImageSettingsRepository =
        KeyValueStoreImageSettingsRepository(
            store = FileKeyValueStore(dataDirectory, IMAGE_SETTINGS_STORE_NAME),
            onQualityChanged = {},
        )
}

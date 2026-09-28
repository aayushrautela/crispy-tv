package com.crispy.tv.distribution

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.crispy.tv.PlaybackDependencies
import com.crispy.tv.screenshot.ScreenshotTestApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The build's distribution contract, asserted per variant.
 *
 * `BuildDistributionComponents` is defined under one name in both
 * `src/store` and `src/sideload`, so this single test compiles for whichever
 * variant is under test and branches on the capabilities it finds. That is the
 * point: if the two ever stop sharing a name, this stops compiling rather than
 * quietly testing only one of them.
 *
 * ## Why the torrent assertion exists
 *
 * `installTorrentResolver` used to be called from `PlaybackDependencies.reset()`,
 * which had no callers. The sideload build therefore shipped
 * `:android:torrent-engine` and then never installed it, so every torrent link
 * hit `UnavailableTorrentResolver` and threw `TorrentSupportUnavailableException`
 * -- while the dex-level distribution check happily reported the engine as
 * present, because the class really was in the APK. Asserting the wiring, not
 * just the packaging, is what catches that class of bug.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    application = ScreenshotTestApplication::class,
    // Robolectric 4.15.1 ships android-all up to SDK 35, and the app targets 36.
    // Without this pin every Robolectric test fails at configuration with
    // "targetSdkVersion=36 > maxSdkVersion=35". The screenshot suite carries the
    // same pin for the same reason.
    sdk = [35],
)
class DistributionComponentsTest {

    private val components: DistributionComponents = BuildDistributionComponents
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun capabilitiesAreInternallyConsistent() {
        val capabilities = components.capabilities

        // A build with no plugin runtime must not advertise the plugins UI, and a
        // build with the UI must have the runtime behind it. These have drifted
        // apart in the past and the result is a settings entry that opens an
        // empty screen.
        assertEquals(
            "pluginsUiSupported and pluginsRuntimeAvailable disagree",
            capabilities.pluginsUiSupported,
            capabilities.pluginsRuntimeAvailable,
        )

        // Every optional engine in this project currently tracks the same axis.
        // Asserted so that the first build to enable one of them alone has to
        // come here and say so, rather than discovering it by surprise.
        val allOff = !capabilities.torrentPlaybackSupported &&
            !capabilities.youtubeInHeroPlaybackSupported &&
            !capabilities.pluginsRuntimeAvailable
        val allOn = capabilities.torrentPlaybackSupported &&
            capabilities.youtubeInHeroPlaybackSupported &&
            capabilities.pluginsRuntimeAvailable
        assertTrue(
            "capabilities split across the axis without a decision: $capabilities",
            allOff || allOn,
        )
    }

    /**
     * The regression this exists for: `installTorrentResolver` used to be called
     * from `PlaybackDependencies.reset()`, which had no callers. The sideload
     * build shipped the engine and never installed it.
     *
     * Exercised through `AppDistribution.install` rather than by calling
     * `installTorrentResolver` directly, because the bug was that *nobody* called
     * it. Testing the component in isolation would have passed while the build
     * stayed broken.
     */
    @Test
    fun installActivatesTheEngineTheVariantClaims() {
        AppDistribution.install(components)

        val resolver = PlaybackDependencies.torrentResolverFactory(context)

        // Compared by name rather than `is NativeTorrentResolver`: the engine is a
        // `sideloadImplementation` dependency, so naming its type here would stop
        // this shared test compiling for the store variant. The name is stable, and
        // a compile-time reference to a sideload-only module from a shared source
        // set is exactly the coupling this refactor set out to remove.
        if (components.capabilities.torrentPlaybackSupported) {
            assertEquals(
                "torrentPlaybackSupported is true, so the resolver must be the real " +
                    "engine and not a stub",
                "com.crispy.tv.torrentengine.NativeTorrentResolver",
                resolver.javaClass.name,
            )
        } else {
            assertEquals(
                "store builds must keep the failing default",
                "com.crispy.tv.PlaybackDependencies\$UnavailableTorrentResolver",
                resolver.javaClass.name,
            )
        }
    }

    @Test
    fun pluginStreamLoaderMatchesThePluginRuntime() {
        if (components.capabilities.pluginsRuntimeAvailable) {
            assertNotNull(
                "a build with the plugin runtime must provide a stream loader",
                components.pluginStreamLoader(context),
            )
        } else {
            assertNull(components.pluginStreamLoader(context))
        }
    }
}

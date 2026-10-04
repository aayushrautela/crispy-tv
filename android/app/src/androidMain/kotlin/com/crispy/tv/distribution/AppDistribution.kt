package com.crispy.tv.distribution

import com.crispy.tv.PlaybackDependencies

/**
 * The build's single read point for everything the variant supplies.
 *
 * ## Why this exists
 *
 * `:app` is becoming a Kotlin Multiplatform library, and the KMP library plugin
 * is single-variant — it cannot have `src/store` and `src/sideload` source
 * sets. The variant lives in `:androidApp`, which keeps the `productFlavors`.
 * That inverts the dependency: `:androidApp` knows the variant, `:app` must not.
 *
 * Four symbols used to be referenced directly from `:app`'s main source set and
 * were each defined in both flavour source sets. They are gathered behind one
 * [DistributionComponents] interface so `:app` has a single distribution seam
 * rather than four, and so the alternative is not four mutable singletons.
 *
 * ## Installation
 *
 * [install] is called once from `CrispyApplication.onCreate`, which is the
 * composition root and lives in `:androidApp` alongside the flavour source sets.
 * Reading before installing throws with that message rather than defaulting,
 * because a silent default would mean a store build quietly behaving like
 * sideload, or the reverse.
 *
 * This is a service locator, which is not the pattern one would choose in green
 * field code. It is chosen because it matches the rest of this codebase --
 * `StreamResolverProvider` and `PlaybackDependencies` are all read the same way -- and
 * because the alternative is a `DistributionComponents` parameter threaded through six
 * factory signatures and a `NavGraphBuilder` extension. Phase 3 is the right
 * place to revisit that if the graph keeps growing.
 */
object AppDistribution {

    private var components: DistributionComponents? = null

    /**
     * Installs the variant's components and activates them.
     *
     * This does more than store a reference, and that is deliberate. The torrent
     * resolver has to be pushed into `PlaybackDependencies` rather than picked up
     * lazily, and it used to be installed from `PlaybackDependencies.reset()` --
     * a method with no callers. The result was a sideload build that shipped
     * `:android:torrent-engine` and then never installed it, so every torrent
     * link threw, while the dex-level check still reported the engine as present
     * because the class genuinely was in the APK.
     *
     * Making activation part of installation means there is no second call to
     * forget. Anything that installs components gets a working build.
     *
     * Called once, from `CrispyApplication.onCreate`.
     */
    fun install(components: DistributionComponents) {
        check(this.components == null) {
            "AppDistribution.install called twice; components are installed once in " +
                "CrispyApplication.onCreate and must not be replaced."
        }
        components.installTorrentResolver(PlaybackDependencies)
        this.components = components
    }

    /**
     * The installed components.
     *
     * @throws IllegalStateException if read before [install].
     */
    val current: DistributionComponents
        get() = checkNotNull(components) {
            "AppDistribution read before install. CrispyApplication.onCreate must " +
                "install the build's DistributionComponents before any UI or " +
                "view model is constructed."
        }
}

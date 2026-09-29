package com.crispy.tv.distribution

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.crispy.tv.PlaybackDependencies
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The install lifecycle of [AppDistribution].
 *
 * ## Why this lives in `:app` and not in `:androidApp`
 *
 * `DistributionComponentsTest` in `:androidApp` asserts what each *variant*
 * supplies. Nothing asserted how the read path behaves: that reading before
 * installing fails, that installing twice fails, and that installing a component
 * activates it. All three are properties of `:app`, so they are tested here,
 * where `:app` is exercised without the application module -- and the
 * application module cannot serve as their home anyway, because
 * `AppDistribution` is an object, so an assertion about *first* install is only
 * meaningful in a classloader that has not installed yet.
 *
 * ## Why the methods are ordered, and why the fake is a companion
 *
 * `AppDistribution` has exactly one install, ever. That is a process-lifetime
 * fact rather than a per-test one, so three separate methods each observe the
 * *cumulative* state: their order is part of their contract, and so is the
 * identity of the object they installed. JUnit builds a fresh test instance per
 * method, and Robolectric reuses one sandbox classloader for every test class
 * with the same `@Config` in the worker JVM -- so an instance field would be a
 * *different* `FakeDistributionComponents` in each method, and the second
 * install would be refused for the wrong reason. Hence `@FixMethodOrder` and a
 * companion object, rather than a reset hook on production code that exists only
 * for tests.
 *
 * The same sharing is why the torrent assertion reads the *factory* rather than
 * `getTorrentResolver`: the resolver instance is cached in a `PlaybackDependencies`
 * field that another test class in this worker may already have populated, and a
 * cache hit would make the assertion pass without install having done anything.
 */
@RunWith(RobolectricTestRunner::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Config(manifest = Config.NONE, sdk = [35])
class AppDistributionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `1 reading before install throws and names the composition root`() {
        val failure = assertFailsWith<IllegalStateException> { AppDistribution.current }

        val why = "the failure has to say who installs, or the reader has no way to know " +
            "what they are meant to do. Actual message: ${failure.message}"
        assertTrue(failure.message.orEmpty().contains("CrispyApplication.onCreate"), why)
    }

    @Test
    fun `2 install publishes the components and activates the variant`() {
        AppDistribution.install(installed)

        val whySame = "AppDistribution.current must hand back exactly what was installed, " +
            "not a re-construction of it"
        assertSame(installed, AppDistribution.current, whySame)

        val whyActivated = "installTorrentResolver has to run as part of installation, or a " +
            "build that ships the engine never installs it and every torrent link throws"
        assertEquals(1, installed.installCalls, whyActivated)

        val whyResolver = "the factory a variant installs must be the one the player later " +
            "reads through PlaybackDependencies, not the failing default that was there " +
            "before install"
        assertSame(
            installed.resolver,
            PlaybackDependencies.torrentResolverFactory(context),
            whyResolver,
        )
    }

    @Test
    fun `3 installing a second time throws instead of replacing the components`() {
        val second = FakeDistributionComponents()

        val failure = assertFailsWith<IllegalStateException> { AppDistribution.install(second) }

        val whyRefused = "a second install has to be refused by name, because the symptom " +
            "otherwise is a replaced distribution whose already-pushed factories still point " +
            "at the other variant's engines. Actual message: ${failure.message}"
        assertTrue(failure.message.orEmpty().contains("called twice"), whyRefused)

        val whyUnchanged = "a refused install must leave the original components in place"
        assertSame(installed, AppDistribution.current, whyUnchanged)

        val whyNotActivated = "a refused install must not have activated the second variant either"
        assertEquals(0, second.installCalls, whyNotActivated)
    }

    private companion object {
        /** Shared by all three methods: see the class comment. */
        val installed = FakeDistributionComponents()
    }
}

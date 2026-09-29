package com.crispy.tv

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.crispy.tv.distribution.RecordingTorrentResolver
import com.crispy.tv.player.TorrentResolver
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The caching contract of [PlaybackDependencies].
 *
 * ## What is being pinned
 *
 * Both lazily-constructed dependencies cache a single instance, and the
 * factories behind them are public `var`s because the composition root installs
 * them. That combination is where the two failure modes live:
 *
 *  - a factory that runs on every read, which is a fresh `AudioFocusManager` per
 *    read and therefore a fresh `AudioManager` and a lost `holderKey`, so audio
 *    focus is never actually contended; and
 *  - an instance cached against whatever `Context` happened to arrive first, so
 *    a component built from an Activity context leaks the Activity for the
 *    life of the process.
 *
 * The first two assertions share a method because they are the same call:
 * splitting them would mean the first to run populates the cache and the second
 * observes a factory that never runs.
 *
 * ## The one thing that owns `torrentResolverInstance`
 *
 * Robolectric reuses a single sandbox classloader across every test class in the
 * worker that shares an `@Config`, so the `PlaybackDependencies` singleton is
 * genuinely process-wide to the whole suite. This class is the only one that
 * calls `getTorrentResolver`, which is what makes the factory-call count an
 * assertion rather than a race. `AppDistributionTest` deliberately reads
 * `torrentResolverFactory` instead for the same reason.
 */
@RunWith(RobolectricTestRunner::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Config(manifest = Config.NONE, sdk = [35])
class PlaybackDependenciesTest {

    private val applicationContext: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `1 the torrent resolver is built once and from the application context`() {
        val resolver = RecordingTorrentResolver()
        var factoryCalls = 0
        val contextsSeenByTheFactory = mutableListOf<Context>()
        PlaybackDependencies.torrentResolverFactory = { context ->
            factoryCalls += 1
            contextsSeenByTheFactory += context
            resolver
        }

        // A ContextWrapper is a distinct Context whose applicationContext is the
        // wrapped one, so passing it makes "the factory was handed the application
        // context" a real assertion rather than a tautology.
        val activityScopedContext = ContextWrapper(applicationContext)

        val first: TorrentResolver = PlaybackDependencies.getTorrentResolver(activityScopedContext)
        val second: TorrentResolver = PlaybackDependencies.getTorrentResolver(activityScopedContext)

        val whySingleton = "the torrent resolver is a process-wide singleton"
        assertSame(first, second, whySingleton)

        val whyFromFactory = "and it must be the instance the factory produced, not a copy"
        assertSame(resolver, first, whyFromFactory)

        val whyOnce = "a factory that runs per read hands the player a new resolver every " +
            "time, so stopAndClear and close act on objects nothing else is holding"
        assertEquals(1, factoryCalls, whyOnce)

        val whyAppScoped = "the resolver must be built from the application context, or it " +
            "retains whatever scoped context reached it first for the life of the process. " +
            "The factory saw: $contextsSeenByTheFactory"
        assertTrue(contextsSeenByTheFactory.all { it === applicationContext }, whyAppScoped)
    }

    @Test
    fun `2 the audio focus manager is built once`() {
        val first = PlaybackDependencies.getAudioFocusManager(applicationContext)
        val second = PlaybackDependencies.getAudioFocusManager(applicationContext)

        val why = "two AudioFocusManagers mean two AudioManagers and two independent " +
            "holderKeys, so playback sources request focus over each other and never hand off"
        assertSame(first, second, why)
    }
}

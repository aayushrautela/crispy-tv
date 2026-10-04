package com.crispy.tv.platform.desktop

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The clocks and the logger.
 *
 * The logger's streams are injected, so these assertions read what it wrote
 * rather than what the console happened to show -- a stub cannot be evidence
 * about the value it replaces, and stdout is not a value.
 */
class DesktopPlatformTest {

    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()
    private val outStream = PrintStream(out, true, StandardCharsets.UTF_8)
    private val errStream = PrintStream(err, true, StandardCharsets.UTF_8)

    private fun logger(debug: Boolean) = DesktopAppLogger(outStream, errStream, debug)

    private fun stdout() = out.toString(StandardCharsets.UTF_8)
    private fun stderr() = err.toString(StandardCharsets.UTF_8)

    @Test
    fun `a wall clock reports a plausible epoch millisecond value`() {
        // Not "is increasing", which a frozen test clock would also satisfy: a
        // value near the real epoch is a check the wall clock and the monotonic
        // clock cannot both pass.
        val now = DesktopTimeSource().nowMs()
        assertTrue(now > 1_600_000_000_000L, "nowMs() = $now is not a wall-clock epoch value")
    }

    @Test
    fun `elapsed time only ever increases`() {
        val clock = DesktopMonotonicClock()
        val first = clock.elapsedMs()
        var last = first
        repeat(200) {
            val next = clock.elapsedMs()
            assertTrue(next >= last, "monotonic clock went backwards: $last then $next")
            last = next
        }
    }

    /**
     * The distinction the interface exists to protect.
     *
     * A monotonic clock's value is meaningless as a timestamp, and a wall clock
     * is unusable as an interval. Swapping one for the other is the bug the
     * `TimeSource` KDoc warns about, so the two are pinned to be distinguishable.
     */
    @Test
    fun `a monotonic clock is not a wall clock`() {
        val elapsed = DesktopMonotonicClock().elapsedMs()
        val wall = DesktopTimeSource().nowMs()
        assertTrue(wall > 1_600_000_000_000L)
        assertTrue(elapsed < 1_000_000L, "elapsedMs() = $elapsed looks like an epoch value")
    }

    @Test
    fun `debug is silent unless the build says it is a debug build`() {
        logger(debug = false).debug("Tag", "hidden")
        assertEquals("", stdout())
    }

    @Test
    fun `debug is written when the build says it is a debug build`() {
        logger(debug = true).debug("Tag", "shown")
        assertTrue(stdout().contains("Tag: shown"), stdout())
    }

    @Test
    fun `info is written regardless of the debug flag`() {
        logger(debug = false).info("Tag", "always")
        assertTrue(stdout().contains("Tag: always"), stdout())
    }

    @Test
    fun `warn and error go to stderr, never to stdout`() {
        val logger = logger(debug = false)
        logger.warn("W", "careful")
        logger.error("E", "broken")

        assertTrue(stderr().contains("W: careful"), stderr())
        assertTrue(stderr().contains("E: broken"), stderr())
        assertEquals("", stdout())
    }

    @Test
    fun `a throwable's stack trace is written, not swallowed`() {
        logger(debug = false).error("E", "failed", IllegalStateException("boom"))
        val text = stderr()
        assertTrue(text.contains("IllegalStateException"), text)
        assertTrue(text.contains("boom"), text)
    }

    @Test
    fun `a null throwable writes the message alone`() {
        logger(debug = false).warn("W", "no cause", null)
        assertTrue(stderr().contains("W: no cause"), stderr())
    }
}

/** [DesktopDistributionCapabilities] and [DesktopPaths]. */
class DesktopCapabilitiesAndPathsTest {

    @Test
    fun `the zero-argument desktop build declares no optional capability`() {
        val capabilities = DesktopDistributionCapabilities()
        assertFalse(capabilities.pluginsUiSupported)
        assertFalse(capabilities.pluginsRuntimeAvailable)
        assertFalse(capabilities.youtubeInHeroPlaybackSupported)
        assertFalse(capabilities.torrentPlaybackSupported)
    }

    @Test
    fun `a capability is declared by the composition root, not by editing a constant`() {
        val capabilities = DesktopDistributionCapabilities(pluginsRuntimeAvailable = true)
        assertTrue(capabilities.pluginsRuntimeAvailable)
        // The others stay false: one capability landing is not all of them.
        assertFalse(capabilities.torrentPlaybackSupported)
    }

    @Test
    fun `the application directory is named per platform convention`() {
        val home = File("/home/tester")
        assertTrue(
            DesktopPaths.applicationDataDirectory(home, mapOf("APPDATA" to "C:\\Users\\t\\AppData\\Roaming"))
                .path.endsWith("Crispy"),
        )
    }

    @Test
    fun `XDG_CONFIG_HOME takes precedence over the default, so cleanup scripts cannot delete it`() {
        val resolved = DesktopPaths.applicationDataDirectory(
            home = File("/home/tester"),
            environment = mapOf("XDG_CONFIG_HOME" to "/custom/config"),
        )
        assertTrue(resolved.path.startsWith("/custom/config"), resolved.path)
    }

    @Test
    fun `XDG_CACHE_HOME takes precedence over the default cache location`() {
        val resolved = DesktopPaths.cacheDirectory(
            home = File("/home/tester"),
            environment = mapOf("XDG_CACHE_HOME" to "/custom/cache"),
        )
        assertTrue(resolved.path.startsWith("/custom/cache"), resolved.path)
    }

    @Test
    fun `the cache root is not the data root, so a cleanup tool cannot delete user state`() {
        val home = File("/home/tester")
        val environment = emptyMap<String, String>()
        val data = DesktopPaths.applicationDataDirectory(home, environment)
        val cache = DesktopPaths.cacheDirectory(home, environment)
        assertNotEquals(data, cache)
        // Both keep the platform convention's directory name.
        assertTrue(data.path.endsWith("Crispy"), data.path)
        assertTrue(cache.path.endsWith("Crispy"), cache.path)
    }
}

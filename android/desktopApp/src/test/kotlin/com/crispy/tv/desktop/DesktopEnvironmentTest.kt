package com.crispy.tv.desktop

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [DesktopEnvironment] as the desktop application constructs it.
 *
 * The point of these is the *seam*, not the stores: `:android:platform-desktop`
 * has its own suites for what each implementation does, and re-asserting them
 * here would only prove the module is on the classpath. What is not covered
 * anywhere else is that a real environment, built the way `main` builds it,
 * hands out six working ports pointing at the directory it was given.
 */
class DesktopEnvironmentTest {

    private val directory: File = createTempDirectory("crispy-env").toFile()

    private fun environment() = DesktopEnvironment(dataDirectory = directory)

    @Test
    fun `settings survive a second environment over the same directory`() {
        environment().settings.putInt("rows", 12)
        assertEquals(12, environment().settings.getInt("rows", 0))
    }

    @Test
    fun `a token written by one environment is readable by another`() {
        val stored = environment().tokens.encrypt("access-token")
        assertEquals("access-token", environment().tokens.decrypt(stored))
    }

    @Test
    fun `a token is not stored as plaintext on disk`() {
        environment().tokens.encrypt("super-secret-value")

        // A real assertion about the file, not about the value the code handed
        // back: the point of encrypting is what lands on disk.
        val onDisk = directory.walkTopDown().filter(File::isFile)
            .filterNot { it.name.endsWith(".key") }
            .joinToString("\n") { it.readText() }

        assertFalse(onDisk.contains("super-secret-value"), "plaintext secret found in $directory")
    }

    @Test
    fun `the key file is not readable by everyone who can read the data directory`() {
        val environment = environment()
        environment.tokens.encrypt("x")
        val key = File(directory, "secrets/token.key")
        assertTrue(key.isFile, "no key file at $key")

        val permissions = runCatching {
            java.nio.file.Files.getPosixFilePermissions(key.toPath())
        }.getOrNull()
        if (permissions != null) {
            // Only meaningful on a filesystem that has POSIX permissions; on
            // Windows the store cannot restrict them and says so at the call.
            assertEquals(
                setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                ),
                permissions,
                "key file is readable by more than its owner",
            )
        }
    }

    @Test
    fun `settings and tokens are separate files, so one cannot clobber the other`() {
        val environment = environment()
        environment.settings.putString("session", "visible")

        // Overwriting the token store must not disturb the settings file, which
        // is the whole point of one file per store name.
        environment.tokens.encrypt("rotated")
        assertEquals("visible", environment().settings.getString("session", null))
    }

    @Test
    fun `the two clocks are distinguishable, so neither can be swapped for the other`() {
        val environment = environment()
        assertTrue(environment.timeSource.nowMs() > 1_600_000_000_000L)
        assertTrue(environment.monotonicClock.elapsedMs() < 1_000_000L)
    }

    @Test
    fun `one monotonic clock is shared, so two readings are comparable`() {
        val environment = environment()
        val first = environment.monotonicClock.elapsedMs()
        val second = environment.monotonicClock.elapsedMs()
        assertTrue(second >= first)
    }

    @Test
    fun `the capability set is the honest one, and it is constructed not defaulted`() {
        val capabilities = environment().capabilities
        assertFalse(capabilities.pluginsRuntimeAvailable)
        assertFalse(capabilities.torrentPlaybackSupported)
        assertFalse(capabilities.youtubeInHeroPlaybackSupported)
        assertFalse(capabilities.pluginsUiSupported)
    }
}

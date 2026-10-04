package com.crispy.tv.desktop

import com.crispy.tv.accounts.Session
import com.crispy.tv.services.DesktopAppServices
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [DesktopAppServices] as the desktop application constructs it, over one
 * directory.
 *
 * `:android:platform-desktop` has its own suites for what each port
 * *implementation* does, and `:app` has suites for what each service built from a
 * port *does* with it. Neither covers the seam between them, which is the only
 * thing here: that the desktop answers are real objects, that they are pointed at
 * one directory, and that the two answers which are genuinely new -- an encrypted
 * session store over a file key, and a Coil cache clear on a JVM -- work when a
 * real build assembles them.
 *
 * This file replaces `DesktopEnvironmentTest`, which asserted the same seams
 * against a class the desktop application no longer has. `DesktopEnvironment` and
 * `DesktopAppServices` were the same composition root twice: its logger, clocks,
 * token store and settings store are `AppServices` members now, so keeping it
 * would have left two roots that could disagree about where data lives.
 */
class DesktopAppServicesTest {

    private val dataDirectory: File = createTempDirectory("crispy-services").toFile()

    /**
     * A second directory because [DesktopAppServices] defaults it to the real
     * per-user cache root, and a test that wrote there would leave something behind
     * on a developer's machine.
     */
    private val cacheDirectory: File = createTempDirectory("crispy-services-cache").toFile()

    /** A second `DesktopAppServices` over the same directories is a restart. */
    private fun services() = DesktopAppServices(
        dataDirectory = dataDirectory,
        cacheDirectory = cacheDirectory,
    )

    @Test
    fun `a session written by one build is readable by the next`() {
        val session = Session(
            accessToken = "access-token-value",
            refreshToken = "refresh-token-value",
            expiresAtEpochSec = 1_900_000_000L,
            userId = "user-1",
            email = "someone@example.test",
            anonymous = false,
        )

        runBlocking { services().tokenStore.save(session) }

        // The claim is not that a session round-trips -- `:backend`'s own suite
        // proves that against a fake store. It is that the *desktop* key file and
        // the *desktop* store together can read what a previous process wrote,
        // which is the entire reason the session store is encrypted rather than
        // kept in memory: a desktop build is a restart, not a re-render.
        assertEquals(session, runBlocking { services().tokenStore.current() })
    }

    @Test
    fun `no file under the data directory holds the session token in plaintext`() {
        runBlocking {
            services().tokenStore.save(
                Session(
                    accessToken = "super-secret-value",
                    refreshToken = "another-secret-value",
                    expiresAtEpochSec = null,
                    userId = null,
                    email = null,
                    anonymous = true,
                ),
            )
        }

        // A real assertion about the files, not about the value the code handed
        // back: the point of encrypting is what lands on disk.
        val sessionFiles = dataDirectory.walkTopDown()
            .filter { it.isFile }
            .filterNot { it.name.endsWith(".key") }
            .toList()

        // Counted *before* reading, and this is the load-bearing half of the case.
        // "No plaintext on disk" is an assertion about absence, so it passes
        // perfectly well when the session was never written at all -- which is
        // exactly what a build whose `tokenStore` is a no-op would do. Naming the
        // file first means that build fails here instead of passing the case it
        // has quietly stopped earning. One file, because this case wrote a session
        // and nothing else: `DesktopAppServices` constructs every store lazily and
        // a second file would be a store this suite does not know about.
        assertEquals(
            1,
            sessionFiles.size,
            "expected only the session store's own file, found ${sessionFiles.map { it.name }}",
        )

        val onDisk = sessionFiles.single().readText()
        assertTrue(onDisk.isNotBlank(), "the session file is empty, so nothing was proven about it")

        assertFalse(onDisk.contains("super-secret-value"), "plaintext token found in $dataDirectory")
        assertFalse(onDisk.contains("another-secret-value"), "plaintext token found in $dataDirectory")
    }

    @Test
    fun `the key file is not readable by everyone who can read the data directory`() {
        runBlocking {
            services().tokenStore.save(
                Session("token", "refresh", null, null, null, true),
            )
        }

        val key = File(dataDirectory, "secrets/token.key")
        assertTrue(key.isFile, "no key file at $key")

        // `DesktopSecretStore` generates the key file on first use rather than at
        // construction, so the assertion above is also the claim that this build
        // actually reached the store rather than constructing it and stopping.
        val permissions = runCatching { Files.getPosixFilePermissions(key.toPath()) }.getOrNull()
        if (permissions != null) {
            // Only meaningful on a filesystem that has POSIX permissions; on
            // Windows the store cannot restrict them and says so at the call.
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                permissions,
                "key file is readable by more than its owner",
            )
        }
    }

    @Test
    fun `a build with no session reports none rather than failing`() {
        assertNull(runBlocking { services().tokenStore.current() })
    }

    @Test
    fun `invalidating the image cache runs off Android`() {
        // The one answer in `DesktopAppServices` that is a *call* rather than a
        // value, and the one this landing decided not to stub: `PlatformContext`
        // is Coil's `expect abstract class` with an `actual typealias` to Android's
        // `Context`, and its JVM `INSTANCE` is what the desktop passes. If that
        // call cannot run on this platform the build fails here rather than on the
        // first image-quality change, months later.
        //
        // **What this case cannot prove**, stated because a smoke test that reads
        // like a guard is worse than none: it does not prove any cache had anything
        // in it, and replacing the body with `Unit` would leave it green. It proves
        // the call executes on the JVM, which is the part that was undecided.
        services().invalidateImageCache()
    }

    @Test
    fun `the graph is given the data directory this build chose`() {
        // Not a tautology: `:app`'s graph builds a file-backed pending-mutation
        // outbox by joining `AppServices.dataDirectoryPath` onto a file name, and
        // every other store resolves relative to the same root. A path that was
        // relative, or resolved twice, would put the outbox somewhere no other
        // store reads -- and nothing else in the repository can see that join.
        assertEquals(dataDirectory.absolutePath, services().dataDirectoryPath)
    }
}
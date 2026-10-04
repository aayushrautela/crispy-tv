package com.crispy.tv.platform.desktop

import java.io.File
import java.util.Locale

/**
 * Where a desktop build keeps its files.
 *
 * ## Why this is a module concern and not an application concern
 *
 * On Android these paths arrive as `context.filesDir` and nobody has to choose
 * them. A desktop process has no such thing, so *something* has to, and if that
 * something is `:desktopApp` then every other desktop entry point -- a second
 * window, a CLI, a test harness -- would have to re-derive the same convention
 * and could get it differently. The convention is part of what makes a value
 * findable, so it belongs beside the stores that write it.
 *
 * A function rather than an `object` with a `val`: resolving the directory reads
 * the environment, and doing that once at class-init would freeze the answer for
 * the life of the process. A test that points the environment at a temporary
 * directory has to be able to ask again.
 *
 * ## The convention
 *
 * `~/.config/crispy` on Linux, `~/Library/Application Support/Crispy` on macOS,
 * `%APPDATA%\Crispy` on Windows -- each the platform's own documented location
 * for application data, which is the point: a user's settings should be where
 * their operating system says settings live, not wherever the app happened to
 * pick. `XDG_CONFIG_HOME` is honoured on Linux because the XDG spec says it
 * takes precedence over the default, and ignoring it is the single most common
 * way a Linux app gets its settings deleted by a careless cleanup script.
 */
object DesktopPaths {

    /** The directory name under the platform's application-data root. */
    const val APPLICATION_DIRECTORY: String = "Crispy"

    /**
     * The application-data root, honouring the platform's environment variable.
     *
     * @param home `System.getProperty("user.home")`, injected so a test can point
     *   this at a temporary directory without touching the real one.
     * @param environment the process environment, injected for the same reason.
     */
    fun applicationDataDirectory(
        home: File = File(System.getProperty("user.home") ?: "."),
        environment: Map<String, String> = System.getenv(),
    ): File = when {
        isWindows -> File(environment["APPDATA"] ?: File(home, "AppData/Roaming").path, APPLICATION_DIRECTORY)
        isMacOs -> File(environment["HOME"] ?: home.path, "Library/Application Support/$APPLICATION_DIRECTORY")
        else -> File(environment["XDG_CONFIG_HOME"] ?: File(home, ".config").path, APPLICATION_DIRECTORY)
    }

    /**
     * The per-user cache root, in the platform's own cache location.
     *
     * The same convention as [applicationDataDirectory] but deliberately a different
     * directory, because the two have different lifetimes: application data is user
     * state and has to survive, while a cache is disposable and an OS cleanup tool is
     * entitled to delete it. Writing HTTP caches under the data root would make that
     * a promise the app cannot keep.
     *
     * `XDG_CACHE_HOME` is honoured on Linux for the same reason as its config
     * counterpart; `LOCALAPPDATA` is the Windows cache location and
     * `~/Library/Caches` the macOS one.
     */
    fun cacheDirectory(
        home: File = File(System.getProperty("user.home") ?: "."),
        environment: Map<String, String> = System.getenv(),
    ): File = when {
        isWindows -> File(environment["LOCALAPPDATA"] ?: File(home, "AppData/Local").path, APPLICATION_DIRECTORY)
        isMacOs -> File(environment["HOME"] ?: home.path, "Library/Caches/$APPLICATION_DIRECTORY")
        else -> File(environment["XDG_CACHE_HOME"] ?: File(home, ".cache").path, APPLICATION_DIRECTORY)
    }

    /**
     * The on-disk OS name, lowercased.
     *
     * Computed per call rather than in a `val` for the same reason as above: it
     * is a read of a system property, and a process that changed it -- or a test
     * that set it -- should see the change. The `?:` fallback matters because
     * `os.name` is not guaranteed to be set in every JVM, and a store that
     * threw while resolving its own directory would be unusable on the platform
     * it was least likely to be tested on.
     */
    private val isWindows: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).startsWith("windows")

    private val isMacOs: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).startsWith("mac")
}

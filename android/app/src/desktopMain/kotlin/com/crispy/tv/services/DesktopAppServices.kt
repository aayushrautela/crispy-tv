package com.crispy.tv.services

import coil3.PlatformContext
import com.crispy.tv.accounts.AccountSessionStore
import com.crispy.tv.accounts.EncryptedAccountSessionStore
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.home.DiskHomeCatalogSnapshotCache
import com.crispy.tv.home.RecommendationCatalogDiskCacheStore
import com.crispy.tv.images.clearImageCache
import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.network.DesktopHttpClients
import com.crispy.tv.platform.AppConfig
import com.crispy.tv.platform.AppLogger
import com.crispy.tv.platform.KeyValueStoreFactory
import com.crispy.tv.platform.MonotonicClock
import com.crispy.tv.platform.TimeSource
import com.crispy.tv.platform.desktop.DesktopAppLogger
import com.crispy.tv.platform.desktop.DesktopMonotonicClock
import com.crispy.tv.platform.desktop.DesktopPaths
import com.crispy.tv.platform.desktop.DesktopSecretStore
import com.crispy.tv.platform.desktop.DesktopTimeSource
import com.crispy.tv.platform.desktop.FileKeyValueStoreFactory
import com.crispy.tv.sync.PluginAddonsSyncBridge
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * The desktop answer to every member of [AppServices].
 *
 * ## Why this file is in `:app` and not in `:android:desktopApp`
 *
 * `AndroidAppServices` is in `:app`'s `androidMain`, and the two are the same
 * kind of thing: the implementation of the app's own port, next to the graph
 * that consumes it. Putting it in the entry-point module instead would make
 * `:desktopApp` the only place that can build an `AppGraph`, which is the same
 * trap in miniature as the five `object` service providers this port replaced --
 * the services would be reachable only from the process that happens to host the
 * `when`. The desktop entry point keeps two jobs and no more: decide the
 * directories, build one `DesktopAppServices` and one `AppGraph`, hand the graph
 * to `DesktopAppRoot`.
 *
 * ## What is a decision here and what is a fact
 *
 * The two directories are constructor parameters **with defaults**, and that is
 * not laziness: this class *is* the desktop composition root, so "which
 * directory" is a decision this file is entitled to make, and the defaults are
 * the answer a packaged build wants. `DesktopPaths` puts application data under
 * the platform's per-user config location and the cache under its cache location,
 * and the difference is a lifetime difference rather than a tidiness one.
 *
 * The home snapshot goes under [dataDirectory] rather than [cacheDirectory]
 * because Android writes it under `filesDir`, and matching that is the point: the
 * cached feed is a cold-start fallback, and a fallback an OS cleanup tool can
 * delete is not one.
 *
 * ## The two answers worth reading rather than scanning
 *
 * [tokenStore] is `EncryptedAccountSessionStore` over `DesktopSecretStore`, not
 * `SecureTokenStore`: the latter is an `androidMain` class, and its codec is
 * `org.json`, which is an `android.jar` platform class rather than a dependency
 * of this project. `DesktopSecretStore` is a *file* key with AES-GCM beside the
 * data rather than a Keystore entry -- its own KDoc says so, and it is the
 * honest ceiling for a desktop build: it protects a token at rest from a
 * shoulder-surfer and from casual grep, and it does not protect it from an
 * attacker who already has your user account.
 *
 * [invalidateImageCache] is the real [clearImageCache] call, and `PlatformContext`
 * is not an Android type on this target -- it is Coil's `expect abstract class`
 * with an `actual typealias` to `Context` on Android, and `INSTANCE` is the JVM's
 * singleton. The honest caveat is that `:app` declares `coil-network-okhttp` and
 * `coil-svg` only in `androidMain`, so the desktop image loader has no fetcher
 * yet and the clear has less to clear. That is a missing dependency, not a
 * missing call, and it is the dependency 3c-4 will add.
 *
 * [createPluginAddonsSyncBridge] answers `null`, which is a real answer and not a
 * stub: `null` means "this platform has no plugin runtime", and desktop has none.
 * The QuickJS bridge is `plugins`' `androidMain`.
 */
class DesktopAppServices(
    private val dataDirectory: File = DesktopPaths.applicationDataDirectory(),
    private val cacheDirectory: File = DesktopPaths.cacheDirectory(),
    debug: Boolean = System.getProperty(DEBUG_PROPERTY) != null,
) : AppServices {

    override val logger: AppLogger = DesktopAppLogger(debug = debug)

    /**
     * Constructed once and shared, and the sharing is the requirement rather than an
     * optimisation: a `MonotonicClock` is an origin plus a reading, so two instances
     * do not share a timeline and an elapsed time measured against the wrong one is
     * a large or negative interval.
     */
    override val monotonicClock: MonotonicClock = DesktopMonotonicClock()

    override val timeSource: TimeSource = DesktopTimeSource()

    /**
     * `Dispatchers.IO` is spelled here and not in `commonMain` because it cannot be
     * spelled there at all -- it is `internal` on Kotlin/Native, so the compiler
     * answers "cannot access", not "unresolved". That is the entire reason
     * `AppServices.ioDispatcher` exists.
     */
    override val ioDispatcher: CoroutineDispatcher = Dispatchers.IO


    private val httpClients = DesktopHttpClients(
        cacheDirectory = cacheDirectory,
        userAgent = userAgent(),
        debugLogging = debug,
    )

    override val httpClient: CrispyHttpClient = httpClients.client

    override val aiHttpClient: CrispyHttpClient = httpClients.aiClient

    override val keyValueStores: KeyValueStoreFactory = FileKeyValueStoreFactory(dataDirectory)

    /**
     * The session, encrypted with a file key and serialised as JSON.
     *
     * The store *name* is this file's decision rather than the store's, which is why
     * it is passed in: `EncryptedAccountSessionStore` takes a `KeyValueStore` because
     * it is a codec over durable storage and knows nothing about where that storage
     * lives. The name matches Android's `auth_tokens_secure` so the two are
     * recognisably the same artefact, but it is **not** a cross-platform format
     * contract -- the two directories have nothing in common, so nothing can ever
     * move a file between them.
     */
    override val tokenStore: AccountSessionStore = EncryptedAccountSessionStore(
        secretStore = DesktopSecretStore(File(dataDirectory, SECRET_KEY_FILE)),
        store = keyValueStores.store(SESSION_STORE_NAME),
    )

    override val homeSnapshotCache: DiskHomeCatalogSnapshotCache = DiskHomeCatalogSnapshotCache(
        RecommendationCatalogDiskCacheStore(
            cacheRoot = dataDirectory.toOkioPath(),
            timeSource = timeSource,
            ioDispatcher = ioDispatcher,
            fileSystem = FileSystem.SYSTEM,
        ),
    )

    override val dataDirectoryPath: String = dataDirectory.absolutePath

    /**
     * Never cancelled, exactly as on Android: it exists for deduplicated home fetches
     * that outlive the screen that started them, and whoever creates it owns a job
     * that outlives every call. Cancelling it is the entry point's decision at exit,
     * not something a service may do for itself.
     */
    override val serviceScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun invalidateImageCache() {
        clearImageCache(PlatformContext.INSTANCE)
    }

    override fun createPluginAddonsSyncBridge(backendClient: CrispyBackendClient): PluginAddonsSyncBridge? = null

    /**
     * `crispytv/<version>`, the shape Android's `AppHttp.buildUserAgent` produces.
     *
     * Android reads the version from `PackageManager` because that is where a
     * packaged Android build's version lives. `AppConfig.VERSION_NAME` is the same
     * value by a different route -- it is generated into `:platform-core`'s
     * `commonMain` from the module's version -- and it is the only version a desktop
     * jar can know. `ifBlank { "dev" }` is Android's own fallback for a build whose
     * version reads empty, kept so the two strings cannot drift apart.
     */
    private fun userAgent(): String =
        "crispytv/" + AppConfig.VERSION_NAME.trim().ifBlank { "dev" }

    private companion object {
        const val DEBUG_PROPERTY = "crispy.debug"
        const val SESSION_STORE_NAME = "auth_tokens_secure"
        const val SECRET_KEY_FILE = "secrets/token.key"
    }
}
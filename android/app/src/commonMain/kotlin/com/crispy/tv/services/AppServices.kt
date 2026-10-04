package com.crispy.tv.services

import com.crispy.tv.accounts.AccountSessionStore
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.home.HomeCatalogSnapshotCache
import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.platform.AppLogger
import com.crispy.tv.platform.KeyValueStoreFactory
import com.crispy.tv.platform.MonotonicClock
import com.crispy.tv.platform.TimeSource
import com.crispy.tv.sync.PluginAddonsSyncBridge
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/**
 * Everything the service graph needs from the platform it happens to be running on, and nothing
 * else.
 *
 * ## Why this exists
 *
 * The shell was already portable — `AppRoot`, `AppNavHost` and six of the seven nav graphs are in
 * `commonMain` — and the desktop still could not boot it, because the graph behind the shell was
 * not portable at all. Eleven ViewModel factories took a `Context`, and each one reached into one
 * of five service providers that also took a `Context`. The chain was shell -> factories ->
 * providers -> `Context`, and every link used that `Context` **only to build something else**: a
 * preferences file, an HTTP client, a token store, a cache directory. A `Context` used for wiring
 * belongs to the factory that does the wiring, so the wiring moved here and the callers now hold
 * this.
 *
 * ## What this is not
 *
 * It is not a service locator: nothing resolves *through* it. Each member is a thing a platform can
 * only produce itself, named after what it answers rather than after the mechanism, and the graph
 * still decides which store is which name. `homeSnapshotCache` is here rather than in the graph
 * because what differs is the *storage* — a directory-backed file on Android, nowhere at all on a
 * desktop build — and that is a platform fact, not a graph decision.
 *
 * ## Nullable is a real answer
 *
 * [createPluginAddonsSyncBridge] returns `null` on a platform with no plugin runtime. That is the
 * same distinction the graph makes everywhere else: `null` means "this platform has no such
 * capability", never "nobody asked".
 */
interface AppServices {
    /** Where diagnostics go. Android logs to logcat; desktop to a file the debug flag names. */
    val logger: AppLogger

    /** The wall clock for anything that needs a date. Injected so a cache can be aged in a test. */
    val timeSource: TimeSource

    /**
     * A clock that cannot go backwards, for measuring durations *within* one run.
     *
     * A member rather than something the graph reaches for because the alternative is a JVM
     * call: `System.nanoTime()` cannot be written in `commonMain`. It is separate from
     * [timeSource] on purpose — an elapsed-time measurement taken against a wall clock is wrong
     * every time the user's clock is adjusted mid-playback, and watch progress is measured in
     * exactly that way.
     */
    val monotonicClock: MonotonicClock

    /** The transport every backend and Supabase call travels over. */
    val httpClient: CrispyHttpClient

    /**
     * A second transport with much longer timeouts, for the model-backed features.
     *
     * Separate from [httpClient] because the timeouts differ by more than an order of magnitude,
     * and sharing one client would make a slow AI request time out on the catalog's budget.
     */
    val aiHttpClient: CrispyHttpClient

    /**
     * Where the account session token lives at rest. Android Keystore; a desktop key file.
     *
     * Typed [AccountSessionStore] rather than [SecretStore], and that is the *second* contract
     * rather than a second shape of the first: `SecureTokenStore` implements both, and none of
     * `SecretStore`'s three members (`isEncrypted` / `encrypt` / `decrypt`) is anything the
     * account stack calls. `AccountSessionStore`'s own KDoc records the same trap — typing the
     * parameter to `SecretStore` would not have compiled, and would have named three unrelated
     * members instead of the real one.
     */
    val tokenStore: AccountSessionStore

    /** Durable storage, by name. The graph chooses the names; the platform chooses the artefact. */
    val keyValueStores: KeyValueStoreFactory

    /** The on-disk copy of the last home snapshot, or nowhere at all. */
    val homeSnapshotCache: HomeCatalogSnapshotCache

    /**
     * An absolute path to a directory the app owns and may write files into, as a `String`.
     *
     * A path rather than a `File`, because `java.io.File` is a JVM type and naming it here
     * would put the pin straight back into every caller. The graph joins this with a file name
     * and converts once, through okio's `toPath()`.
     *
     * Narrower than it looks on purpose: this is where *durable app state the platform chose a
     * home for* goes, and it is not a general scratch space. Android answers `filesDir`, a
     * desktop answers its per-user config directory.
     */
    val dataDirectoryPath: String

    /**
     * The dispatcher to use for blocking file and IO work.
     *
     * A member rather than something the graph picks because `Dispatchers.IO` **cannot be
     * written in `commonMain` at all** — it is `internal` on Kotlin/Native. `Dispatchers.Default`
     * would compile everywhere and put file IO on a CPU-sized pool, so the choice is made once
     * at the platform edge and the graph reads it back.
     */
    val ioDispatcher: CoroutineDispatcher

    /**
     * A scope that lives as long as the process, for work that outlives the screen that started it
     * — deduplicated home fetches, mostly.
     *
     * Required rather than defaulted on purpose: a scope built with `Dispatchers.IO` cannot even be
     * *written* in `commonMain` (`Dispatchers.IO` is `internal` on Kotlin/Native), and a defaulted
     * one would hide the lifetime decision from every call site.
     */
    val serviceScope: CoroutineScope

    /** Drops decoded artwork. Called after a profile switch, which changes every image URL. */
    fun invalidateImageCache()

    /**
     * The bridge to the QuickJS plugin runtime, or `null` where there is no plugin runtime.
     *
     * Takes the backend client because the bridge reads addon manifests through it, and returning
     * it rather than holding it is what lets this stay a two-line answer on a platform without one.
     */
    fun createPluginAddonsSyncBridge(backendClient: CrispyBackendClient): PluginAddonsSyncBridge?
}
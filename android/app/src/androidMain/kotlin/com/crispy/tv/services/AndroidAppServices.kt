package com.crispy.tv.services

import android.content.Context
import com.crispy.tv.accounts.AccountSessionStore
import com.crispy.tv.accounts.SecureTokenStore
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.home.DiskHomeCatalogSnapshotCache
import com.crispy.tv.home.HomeCatalogSnapshotCache
import com.crispy.tv.home.RecommendationCatalogDiskCacheStore
import com.crispy.tv.images.clearImageCache
import com.crispy.tv.network.AppHttp
import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.platform.AppLogger
import com.crispy.tv.platform.KeyValueStoreFactory
import com.crispy.tv.platform.MonotonicClock
import com.crispy.tv.platform.TimeSource
import com.crispy.tv.platform.android.AndroidAppLogger
import com.crispy.tv.platform.android.AndroidMonotonicClock
import com.crispy.tv.platform.android.AndroidTimeSource
import com.crispy.tv.platform.android.ContextKeyValueStoreFactory
import com.crispy.tv.sync.PluginAddonsSyncBridge
import com.crispy.tv.sync.PluginSyncBridgeProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * Android's answer to [AppServices], and the only place a `Context` reaches this wiring.
 *
 * Every member below is built from the application context and nothing else: a preferences file, an
 * HTTP client, a Keystore-backed token store, a cache directory. That is [AppServices]' whole
 * reason to exist — the callers used to do this work themselves, each one holding a `Context` it
 * only needed in order to build something else.
 *
 * ## Lifetime
 *
 * One instance per process, created by `AndroidAppRoot` and remembered against the application
 * context, because [AppHttp] and [SecureTokenStore] both cache internally and a second instance
 * would mean a second cache. The scope is created here rather than injected because *this* is the
 * platform answer: it is the one place that is allowed to name `Dispatchers.IO`, which
 * `commonMain` cannot.
 */
class AndroidAppServices(context: Context) : AppServices {
    private val appContext: Context = context.applicationContext

    /**
     * Shared with the disk cache so both read the same clock. Two instances would be two answers
     * to "now", and a cache aged against one and stamped by the other would expire at the wrong
     * moment.
     */
    private val androidTimeSource = AndroidTimeSource()

    override val logger: AppLogger = AndroidAppLogger(appContext)

    override val timeSource: TimeSource
        get() = androidTimeSource

    override val monotonicClock: MonotonicClock = AndroidMonotonicClock()

    override val httpClient: CrispyHttpClient = AppHttp.client(appContext)

    override val aiHttpClient: CrispyHttpClient = AppHttp.aiClient(appContext)

    override val tokenStore: AccountSessionStore = SecureTokenStore(appContext)

    override val keyValueStores: KeyValueStoreFactory = ContextKeyValueStoreFactory(appContext)

    override val homeSnapshotCache: HomeCatalogSnapshotCache = DiskHomeCatalogSnapshotCache(
        RecommendationCatalogDiskCacheStore(
            cacheRoot = appContext.filesDir.toOkioPath(),
            timeSource = androidTimeSource,
            ioDispatcher = Dispatchers.IO,
            fileSystem = FileSystem.SYSTEM,
        ),
    )

    override val serviceScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * `filesDir` rather than `cacheDir`: the optimistic mutation outbox is durable state that has
     * to survive the process being killed mid-replay, and Android may reclaim `cacheDir` under
     * storage pressure, which would silently drop queued writes.
     */
    override val dataDirectoryPath: String = appContext.filesDir.absolutePath

    override val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    override fun invalidateImageCache() {
        clearImageCache(appContext)
    }

    override fun createPluginAddonsSyncBridge(backendClient: CrispyBackendClient): PluginAddonsSyncBridge? =
        PluginSyncBridgeProvider.create(appContext, backendClient)
}
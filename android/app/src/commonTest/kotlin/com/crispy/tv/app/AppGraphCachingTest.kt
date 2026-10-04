package com.crispy.tv.app

import com.crispy.tv.accounts.AccountSessionStore
import com.crispy.tv.accounts.Session
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.home.NoHomeCatalogSnapshotCache
import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.network.CrispyHttpResponse
import com.crispy.tv.network.HttpRequest
import com.crispy.tv.platform.AppLogger
import com.crispy.tv.platform.KeyValueStore
import com.crispy.tv.platform.KeyValueStoreFactory
import com.crispy.tv.platform.MonotonicClock
import com.crispy.tv.platform.TimeSource
import com.crispy.tv.services.AppServices
import com.crispy.tv.sync.PluginAddonsSyncBridge
import com.crispy.tv.testing.FakeKeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * The identity contract of [AppGraph]: what is one instance per graph, and what is deliberately
 * not.
 *
 * This suite replaces `ServiceProviderCachingTest`, which pinned the same property about two
 * cached `object` providers. It is in `commonTest` rather than `androidHostTest` because the graph
 * under test is in `commonMain`, and it can reach three members the old suite could not:
 * `accountClient`, `backendClient` and `homeCatalogService` all used to be untestable on the JVM
 * because the provider they were read from reached `SecureTokenStore`, whose constructor calls
 * `KeyStore.getInstance("AndroidKeyStore")` and fails with `KeyStoreException` before any assertion
 * runs. **The reason they are reachable now is the port, not the test classpath**: the graph takes
 * an [AppServices], so a test supplies a token store and there is no keystore in the picture. A fake
 * keystore would have proved nothing about the real one; the fake *port* proves the thing that was
 * actually untested.
 *
 * The cost of getting identity wrong is not a leak but a second copy of the session: two backend
 * clients are two HTTP connection pools and two sets of auth headers for one user, and two
 * `ActiveProfileStore`s are two writers to the same file.
 *
 * ## The store names are a contract, and they are asserted literally
 *
 * `supabase_sync_lab`, `pending_provider_auth`, `profile_data_shadow`, `playback_settings`,
 * `image_settings` and `ai_insights_cache` are the names every install that has already run has its
 * state under. Renaming one strands it. They are declared as private constants inside [AppGraph], so
 * a reader of the graph sees them and a test that renamed one would fail here — which is the point:
 * a test written from the constant names would agree with any rename.
 */
class AppGraphCachingTest {

    private val services = FakeAppServices()
    private val graph = AppGraph(services)

    @Test
    fun everyMemberIsBuiltOncePerGraph() {
        val why = "two backend clients are two connection pools and two sets of auth headers " +
            "for one user; two profile stores are two writers to one file"

        assertSame(graph.backendClient, graph.backendClient, why)
        assertSame(graph.accountClient, graph.accountClient, why)
        assertSame(graph.activeProfileStore, graph.activeProfileStore, why)
        assertSame(graph.backendContextResolver, graph.backendContextResolver, why)
        assertSame(graph.homeCatalogService, graph.homeCatalogService, why)
        assertSame(graph.playbackSettingsRepository, graph.playbackSettingsRepository, why)
        assertSame(graph.imageSettingsRepository, graph.imageSettingsRepository, why)
        assertSame(graph.aiInsightsRepository, graph.aiInsightsRepository, why)
    }

    @Test
    fun theGraphAsksForEachStoreOnceAndUnderItsDurableName() {
        // Read every member that opens a store, plus the one store reachable only through
        // `createProfileDataCloudSync` -- which is why the count is asserted *after* the call and
        // not from the `by lazy` reads alone.
        graph.activeProfileStore
        graph.pendingProviderAuthStore
        graph.playbackSettingsRepository
        graph.imageSettingsRepository
        graph.aiInsightsRepository
        graph.createProfileDataCloudSync()

        assertEquals(
            listOf(
                "supabase_sync_lab",
                "pending_provider_auth",
                "playback_settings",
                "image_settings",
                "ai_insights_cache",
                "profile_data_shadow",
            ),
            services.requestedStoreNames,
            "these names are where every install that has already run keeps its state; " +
                "the order is the order the graph opens them, and a rename strands the data",
        )
    }

    @Test
    fun thePerCallBuildersAreNotShared() {
        // These were built per call before the graph existed, and making them singletons would be a
        // behaviour change dressed as a refactor. The assertion is here so that a later reader who
        // "tidies" them into `by lazy` members finds a failing test rather than making the change
        // silently.
        val why = "these were fresh per call before the graph; a shared instance is a behaviour " +
            "change, not a cleanup"

        assertNotSame(graph.backendBrowseRepository(), graph.backendBrowseRepository(), why)
        assertNotSame(graph.backendSearchRepository(), graph.backendSearchRepository(), why)
        assertNotSame(graph.aiSearchRepository(), graph.aiSearchRepository(), why)
        assertNotSame(graph.createProfileDataCloudSync(), graph.createProfileDataCloudSync(), why)
        assertNotSame(graph.activeProfileLoader(), graph.activeProfileLoader(), why)
    }

    @Test
    fun buildingTheGraphIssuesNoRequest() {
        // Every member above is a `by lazy`, so this suite constructs the whole account and backend
        // stack. Construction must not touch the network: a member that reached out while being
        // built would make the first screen pay for the graph, and the refusal in the double would
        // fail this test rather than being swallowed.
        graph.accountClient
        graph.backendClient
        graph.backendContextResolver
        graph.homeCatalogService
        graph.aiInsightsRepository
        graph.activeProfileLoader()

        assertEquals(
            emptyList(),
            services.httpCalls,
            "building the graph must not issue a request",
        )
    }
}

/**
 * The minimum a platform has to answer for [AppGraph].
 *
 * Every member is a real implementation rather than a `TODO()` that throws, because the graph
 * *constructs* all of them and a throwing double would make this suite a test of construction
 * order. The two that genuinely cannot be exercised here — [httpClient] and [tokenStore] — answer
 * in a way that says so: the transport refuses every call and the session store is empty.
 */
private class FakeAppServices : AppServices {
    val requestedStoreNames = mutableListOf<String>()
    val httpCalls = mutableListOf<String>()

    private val stores = mutableMapOf<String, KeyValueStore>()

    override val logger: AppLogger = object : AppLogger {
        override fun debug(tag: String, message: String) = Unit
        override fun info(tag: String, message: String) = Unit
        override fun warn(tag: String, message: String, throwable: Throwable?) = Unit
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }

    override val timeSource = TimeSource { 0L }
    override val monotonicClock = MonotonicClock { 0L }

    override val httpClient: CrispyHttpClient = RecordingHttpClient(httpCalls)
    override val aiHttpClient: CrispyHttpClient = httpClient

    override val tokenStore: AccountSessionStore = object : AccountSessionStore {
        override fun current(): Session? = null
        override suspend fun save(session: Session) = Unit
        override suspend fun clear() = Unit
    }

    override val keyValueStores = KeyValueStoreFactory { name ->
        requestedStoreNames += name
        // Cached per name, as the port's own KDoc requires: the same name must always answer with
        // the same storage, so a caller is entitled to hold on to the result.
        stores.getOrPut(name) { FakeKeyValueStore() }
    }

    override val homeSnapshotCache = NoHomeCatalogSnapshotCache
    override val dataDirectoryPath: String = "/unused-in-commonTest"
    override val ioDispatcher = Dispatchers.Default
    override val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun invalidateImageCache() = Unit

    override fun createPluginAddonsSyncBridge(backendClient: CrispyBackendClient): PluginAddonsSyncBridge? =
        null
}

/** Refuses every request, and records that it was asked. Nothing in the graph may call it. */
private class RecordingHttpClient(
    private val calls: MutableList<String>,
) : CrispyHttpClient {
    private fun refuse(method: String, url: String): Nothing {
        calls += "$method $url"
        error("the graph must not issue a request while building: $method $url")
    }

    override suspend fun execute(
        request: HttpRequest,
        callTimeoutMs: Long?,
    ): CrispyHttpResponse = refuse(request.method.name, request.url)

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        callTimeoutMs: Long?,
        query: List<Pair<String, String>>,
    ): CrispyHttpResponse = refuse("GET", url)

    override suspend fun getOrNull(
        url: String,
        headers: Map<String, String>,
        callTimeoutMs: Long?,
        query: List<Pair<String, String>>,
    ): CrispyHttpResponse? = refuse("GET", url)

    override suspend fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String>,
        callTimeoutMs: Long?,
    ): CrispyHttpResponse = refuse("POST", url)

    override suspend fun delete(
        url: String,
        headers: Map<String, String>,
        callTimeoutMs: Long?,
    ): CrispyHttpResponse = refuse("DELETE", url)
}
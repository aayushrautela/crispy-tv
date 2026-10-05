package com.crispy.tv.app

import com.crispy.tv.accounts.AccountBootstrapRepository
import com.crispy.tv.accounts.AccountSettingsRepository
import com.crispy.tv.accounts.ActiveProfileInfo
import com.crispy.tv.accounts.ActiveProfileStore
import com.crispy.tv.accounts.DefaultAccountBootstrapRepository
import com.crispy.tv.accounts.PendingProviderAuthStore
import com.crispy.tv.accounts.ProfileRepository
import com.crispy.tv.accounts.SupabaseAccountClient
import com.crispy.tv.accounts.SyncProviderRepository
import com.crispy.tv.accounts.loadActiveProfile
import com.crispy.tv.addons.registry.MetadataAddonRegistry
import com.crispy.tv.addons.sources.BackendEpisodeListProvider
import com.crispy.tv.ai.AiInsightsCacheStore
import com.crispy.tv.ai.AiInsightsRepository
import com.crispy.tv.backend.BackendContextResolver
import com.crispy.tv.backend.CachingBackendContextResolver
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.data.repository.DefaultCatalogRepository
import com.crispy.tv.data.repository.DefaultSessionRepository
import com.crispy.tv.data.repository.DefaultUserMediaRepository
import com.crispy.tv.discover.BackendBrowseRepository
import com.crispy.tv.domain.repository.CatalogRepository
import com.crispy.tv.domain.repository.SessionRepository
import com.crispy.tv.domain.repository.UserMediaRepository
import com.crispy.tv.home.CachingHomeCatalogService
import com.crispy.tv.home.HomeCatalogService
import com.crispy.tv.platform.AppConfig
import com.crispy.tv.player.WatchHistoryService
import com.crispy.tv.search.AiSearchRepository
import com.crispy.tv.search.BackendSearchRepository
import com.crispy.tv.services.AppServices
import com.crispy.tv.settings.ImageSettingsRepository
import com.crispy.tv.settings.KeyValueStoreImageSettingsRepository
import com.crispy.tv.settings.KeyValueStorePlaybackSettingsRepository
import com.crispy.tv.settings.PlaybackSettingsRepository
import com.crispy.tv.sync.HouseholdAddonsCloudSync
import com.crispy.tv.sync.ProfileDataCloudSync
import com.crispy.tv.sync.ProfileDataShadowStore
import com.crispy.tv.watchhistory.BackendWatchHistoryService
import com.crispy.tv.watchhistory.WatchHistoryConfig


// The names the durable stores are written under. They are a data-compatibility contract with
// every install that has already run, so they are literal strings and they are declared here,
// once, next to the only code that asks for them.
//
// They used to sit beside the `SharedPreferences` handles that opened them, on the reasoning
// that "the file is a platform concern". That reasoning was about the *artefact*, and the
// name is not the artefact: on a desktop the same name selects a different file, and a
// platform that cannot keep the name with the data would strand it. What is genuinely the
// platform's is `services.keyValueStores`, which is what turns a name into storage.
private const val ACTIVE_PROFILE_STORE = "supabase_sync_lab"
private const val PENDING_PROVIDER_AUTH_STORE = "pending_provider_auth"
private const val PROFILE_DATA_SHADOW_STORE = "profile_data_shadow"
private const val PLAYBACK_SETTINGS_STORE = "playback_settings"
private const val IMAGE_SETTINGS_STORE = "image_settings"
private const val AI_INSIGHTS_CACHE_STORE = "ai_insights_cache"
private const val WATCH_HISTORY_PROGRESS_STORE = "watch_progress"

/**
 * The service graph, in `commonMain`, built from one [AppServices].
 *
 * ## What moved here, and what did not
 *
 * Five cached `object` service providers and eleven `Context`-taking factory functions used to
 * stand between the shared shell and its services. Every one of them used that `Context` **only
 * to build something else** -- a preferences file, an HTTP client, a token store, a cache
 * directory -- and a `Context` used for wiring belongs to the factory that does the wiring. So
 * the wiring moved into [AppServices] and the callers hold this instead.
 *
 * What is left in `androidMain` is what genuinely needs the platform, and it is not a small
 * remainder: the player. `homeViewModelFactory` reaches for a raw OkHttp client rather than the
 * [com.crispy.tv.network.CrispyHttpClient] this graph holds, `homeSelectorViewModelFactory` and
 * `detailsViewModelFactory` need `StreamResolverProvider`, `PlayerStreamHandoff` and
 * `AppDistribution`, and the remaining `PlaybackDependencies` seams are `(Context) ->` factories
 * for the playback controller, the torrent resolver, audio focus, the intro-skip service and the
 * stream resolver. Those keep a `Context` until the desktop player lands, because pulling them
 * forward would drag the player forward with them.
 *
 * `PlaybackDependencies` used to be described here as "a set of `(Context) ->` seams the
 * `:androidApp` store and sideload variants install into". **Nothing installs into it.** The only
 * assignments to any of its members in the whole tree are `DistributionComponents`, which writes
 * `torrentResolverFactory` from inside `:app`, and one test. Watch history was the member that
 * premise was really about, and it has now joined this class above -- see
 * [watchHistoryService].
 *
 * ## One instance per process
 *
 * Every member is a `by lazy`, because several of these hold caches and at least two of them
 * hold a writer to a store another member also writes. `AndroidAppServices` is constructed once
 * by `CrispyApplication` and this graph is constructed once from it, so the whole tree is one
 * instance per process -- which is exactly the contract
 * `androidHostTest/.../AppGraphCachingTest` pins.
 *
 * ## What this is not
 *
 * Not a service locator: nothing resolves *through* this. Each member is built here from
 * [AppServices], and a member that used to be a fresh instance per call stays a function
 * ([backendBrowseRepository], [backendSearchRepository], [aiSearchRepository],
 * [createProfileDataCloudSync], [createHouseholdAddonsCloudSync]) rather than becoming a
 * `by lazy`, because making those singletons would be a behaviour change dressed as a refactor.
 */
class AppGraph(
    /**
     * The platform products this graph was built from.
     *
     * `internal` rather than `public` because nothing outside `:app` may reach them, and
     * `internal` rather than a second constructor parameter because there must be exactly one
     * set per graph: [AndroidAppGraph] reads these same instances for the members it could not
     * move, and a second `AppServices` would be a second `SecureTokenStore`, a second disk
     * snapshot cache and a second service scope for one process.
     */
    internal val services: AppServices,
) {
    /**
     * The Supabase half of the account stack.
     *
     * `tokenStore` and both clients come from [AppServices]; the two URL constants do not, and
     * that is deliberate. `AppConfig` is generated into `:platform-core`'s `commonMain` and is
     * already read from `:app`'s `commonMain`, so naming it here is not a new reach.
     */
    val accountClient: SupabaseAccountClient by lazy {
        SupabaseAccountClient(
            httpClient = services.httpClient,
            supabaseUrl = AppConfig.SUPABASE_URL,
            supabasePublishableKey = AppConfig.SUPABASE_PUBLISHABLE_KEY,
            tokenStore = services.tokenStore,
            // A defaulted `System.currentTimeMillis()` here would compile on every target this
            // repository builds and silently keep the pin the parameter exists to remove --
            // which is the same argument `SupabaseAccountClient` makes about this parameter.
            nowMs = { services.timeSource.nowMs() },
        )
    }

    /** The backend half. `aiHttpClient` is separate because its timeouts are an order apart. */
    val backendClient: CrispyBackendClient by lazy {
        CrispyBackendClient(
            httpClient = services.httpClient,
            backendUrl = AppConfig.CRISPY_BACKEND_URL,
            aiHttpClient = services.aiHttpClient,
        )
    }

    val activeProfileStore: ActiveProfileStore by lazy {
        ActiveProfileStore(services.keyValueStores.store(ACTIVE_PROFILE_STORE))
    }

    val backendContextResolver: BackendContextResolver by lazy {
        CachingBackendContextResolver(
            supabaseAccountClient = accountClient,
            activeProfileStore = activeProfileStore,
            backendClient = backendClient,
        )
    }

    val bootstrapRepository: AccountBootstrapRepository by lazy {
        DefaultAccountBootstrapRepository(
            // The effect is named, not the mechanism: the Coil-shaped `clearImageCache(context)`
            // becomes a capability the platform answers, and the repository keeps asking for the
            // same thing.
            clearImageCache = services::invalidateImageCache,
            supabase = accountClient,
            backendContextResolver = backendContextResolver,
            backendClient = backendClient,
            activeProfileStore = activeProfileStore,
            // Still the keystore implementation. The repository names the port, so this line is
            // the one place that says which of the two it is, and it can change without the
            // repository noticing.
            tokenStore = services.tokenStore,
        )
    }

    val syncProviderRepository: SyncProviderRepository by lazy {
        SyncProviderRepository(
            backendContextResolver = backendContextResolver,
            backendClient = backendClient,
        )
    }

    val profileRepository: ProfileRepository by lazy {
        ProfileRepository(
            backendContextResolver = backendContextResolver,
            backendClient = backendClient,
        )
    }

    val accountSettingsRepository: AccountSettingsRepository by lazy {
        AccountSettingsRepository(backendClient = backendClient)
    }

    val pendingProviderAuthStore: PendingProviderAuthStore by lazy {
        // Renaming this store would strand the in-flight OAuth state of every install that has
        // one. The key names are in `PendingProviderAuthStore` itself; only the file name is here.
        PendingProviderAuthStore(services.keyValueStores.store(PENDING_PROVIDER_AUTH_STORE))
    }

    val homeCatalogService: HomeCatalogService by lazy {
        // The scope is the service's own job, uncancelled, exactly as it was when the service
        // built it for itself -- `AppServices.serviceScope` is the same answer
        // (`SupervisorJob` on the IO dispatcher) and sharing it is what lets the two outlive
        // each other correctly.
        CachingHomeCatalogService(
            backendClient = backendClient,
            backendContextResolver = backendContextResolver,
            // `services.homeSnapshotCache` rather than a second disk cache built here. The
            // provider this replaces built a `DiskHomeCatalogSnapshotCache` of its own, and the
            // store behind it is purely file-backed with no in-memory map, so the duplicate was
            // cosmetic -- but two instances would still be two answers to "what is on disk".
            cache = services.homeSnapshotCache,
            serviceScope = services.serviceScope,
        )
    }

    val playbackSettingsRepository: PlaybackSettingsRepository by lazy {
        KeyValueStorePlaybackSettingsRepository(services.keyValueStores.store(PLAYBACK_SETTINGS_STORE))
    }

    val imageSettingsRepository: ImageSettingsRepository by lazy {
        // The Coil cache clear is wired here rather than inside the repository because it is the
        // one effect of a quality change that is not a stored value, and Coil does not exist off
        // Android. `AppServices.invalidateImageCache` is what makes that statement portable.
        KeyValueStoreImageSettingsRepository(
            store = services.keyValueStores.store(IMAGE_SETTINGS_STORE),
            onQualityChanged = { services.invalidateImageCache() },
        )
    }

    private val profileDataShadowStore: ProfileDataShadowStore by lazy {
        ProfileDataShadowStore(services.keyValueStores.store(PROFILE_DATA_SHADOW_STORE))
    }

    val aiInsightsRepository: AiInsightsRepository by lazy {
        // No language tag, deliberately. `languageTag` is a per-call parameter on both of this
        // repository's members, because the cache is keyed by `(itemId, languageTag)` and one
        // instance has to serve every language the device reports. The value crosses from the
        // call site instead -- `DetailsUseCases` declares both of its lambdas that way.
        AiInsightsRepository(
            supabase = accountClient,
            activeProfileStore = activeProfileStore,
            backend = backendClient,
            cache = AiInsightsCacheStore(services.keyValueStores.store(AI_INSIGHTS_CACHE_STORE)),
        )
    }

    val sessionRepository: SessionRepository by lazy {
        DefaultSessionRepository(accountClient)
    }

    val catalogRepository: CatalogRepository by lazy {
        DefaultCatalogRepository(backendClient)
    }

    /**
     * Watch progress: the local store, the backend half, and the two things built on top of them.
     *
     * All three used to be reached through `PlaybackDependencies`, a set of `(Context) ->`
     * factories in `androidMain`. That indirection carried a KDoc saying `:androidApp`'s `store`
     * and `sideload` variants install into it, and **nothing installs into it**: the only
     * assignment to any `PlaybackDependencies` member anywhere in the tree is a test's, and it
     * writes `torrentResolverFactory`, which `DistributionComponents` also writes from inside
     * `:app`. So the question the locator existed to answer -- "who provides watch history" --
     * had exactly one answer, and a desktop could not reach it without writing into an
     * `androidMain` global. That is a construction here instead.
     *
     * Collapsing the instances is not a behaviour change. `BackendWatchHistoryService` holds its
     * collaborators as constructor `val`s and writes through the [WATCH_HISTORY_PROGRESS_STORE]
     * handle it is given, with no in-memory cache for a second copy to own; `DefaultUserMediaRepository`
     * is a delegating wrapper over it with no state of its own. What did change is the count:
     * there is now one watch-history service per process rather than one per factory call.
     */
    val watchHistoryService: WatchHistoryService by lazy {
        BackendWatchHistoryService(
            progressStore = services.keyValueStores.store(WATCH_HISTORY_PROGRESS_STORE),
            timeSource = services.timeSource,
            // Off [services] rather than built here, and that is the whole reason this member
            // moved: `monotonicClock` is the same clock this service would otherwise construct,
            // and two constructions of a clock is two answers to "how long has this been playing".
            monotonicClock = services.monotonicClock,
            logger = services.logger,
            backend = backendClient,
            backendContextResolver = backendContextResolver,
            // The same provider the player used to build through `episodeListProviderFactory`,
            // over the same two clients -- a third construction of it was never the question.
            episodeListProvider = BackendEpisodeListProvider(
                supabaseAccountClient = accountClient,
                backendClient = backendClient,
            ),
            config = WatchHistoryConfig(appVersion = AppConfig.VERSION_NAME),
        )
    }

    val userMediaRepository: UserMediaRepository by lazy {
        DefaultUserMediaRepository(watchHistoryService)
    }

    /**
     * Builds the profile-settings sync, naming both of the stores it persists through.
     *
     * A function rather than a `by lazy` because the provider this replaces built a fresh
     * instance per call, and the caller wraps it in its own `remember`.
     */
    fun createProfileDataCloudSync(): ProfileDataCloudSync =
        ProfileDataCloudSync(
            supabase = accountClient,
            backend = backendClient,
            playbackSettings = playbackSettingsRepository,
            activeProfileStore = activeProfileStore,
            shadowStore = profileDataShadowStore,
        )

    /**
     * Builds the household addons sync.
     *
     * The plugin bridge is a function on [AppServices] rather than a value because it is the
     * one collaborator that is `null` on a platform with no plugin runtime, and that is a real
     * answer rather than a missing one.
     */
    fun createHouseholdAddonsCloudSync(addonRegistry: MetadataAddonRegistry): HouseholdAddonsCloudSync =
        HouseholdAddonsCloudSync(
            supabase = accountClient,
            backend = backendClient,
            addonRegistry = addonRegistry,
            activeProfileStore = activeProfileStore,
            logger = services.logger,
            pluginSyncBridge = services.createPluginAddonsSyncBridge(backendClient),
        )

    /**
     * The [loadProfile] slot that the common `ProfileMenuRoute` and `ProfileIconButton` take.
     *
     * It returns a lambda rather than the profile itself so the three collaborators are read
     * once here and the common code never names any of them. **Callers must wrap the result in
     * `remember`**: the lambda's identity is a `produceState` key, so a fresh lambda on every
     * recomposition restarts the load each time.
     */
    fun activeProfileLoader(): suspend () -> ActiveProfileInfo? {
        val supabase = accountClient
        val backend = backendClient
        val store = activeProfileStore
        return { loadActiveProfile(supabase, backend, store) }
    }

    // Fresh per call, on purpose: these three used to be built per call, and a `by lazy` here
    // would be a behaviour change rather than a refactor.

    fun backendBrowseRepository(): BackendBrowseRepository =
        BackendBrowseRepository(supabase = accountClient, backend = backendClient)

    fun backendSearchRepository(): BackendSearchRepository =
        BackendSearchRepository(supabase = accountClient, backend = backendClient)

    fun aiSearchRepository(): AiSearchRepository =
        AiSearchRepository(
            supabase = accountClient,
            activeProfileStore = activeProfileStore,
            backend = backendClient,
        )
}
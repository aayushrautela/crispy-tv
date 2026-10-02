package com.crispy.tv.accounts

import android.content.Context
import com.crispy.tv.images.clearImageCache
import com.crispy.tv.platform.AppConfig
import com.crispy.tv.backend.BackendContextResolverProvider
import com.crispy.tv.backend.BackendServicesProvider
import com.crispy.tv.home.RecommendationCatalogDiskCacheStore
import com.crispy.tv.home.HomeCatalogService
import com.crispy.tv.home.CachingHomeCatalogService
import com.crispy.tv.addons.registry.MetadataAddonRegistry
import com.crispy.tv.platform.android.AndroidAppLogger
import com.crispy.tv.network.AppHttp
import com.crispy.tv.sync.HouseholdAddonsCloudSync
import com.crispy.tv.sync.PluginSyncBridgeProvider
import com.crispy.tv.sync.ProfileDataCloudSync
import com.crispy.tv.settings.PlaybackSettingsRepositoryProvider
import com.crispy.tv.platform.android.SharedPreferencesKeyValueStore
import com.crispy.tv.platform.android.AndroidTimeSource

object SupabaseServicesProvider {
    @Volatile
    private var supabaseAccountClient: SupabaseAccountClient? = null

    @Volatile
    private var activeProfileStore: ActiveProfileStore? = null

    @Volatile
    private var secureTokenStore: SecureTokenStore? = null

    @Volatile
    private var homeCatalogService: HomeCatalogService? = null

    fun secureTokenStore(context: Context): SecureTokenStore {
        secureTokenStore?.let { return it }
        synchronized(this) {
            secureTokenStore?.let { return it }
            val created = SecureTokenStore(context.applicationContext)
            secureTokenStore = created
            return created
        }
    }

    fun accountClient(context: Context): SupabaseAccountClient {
        supabaseAccountClient?.let { return it }
        synchronized(this) {
            supabaseAccountClient?.let { return it }
            val appContext = context.applicationContext
            val created =
                SupabaseAccountClient(
                    httpClient = AppHttp.client(appContext),
                    supabaseUrl = AppConfig.SUPABASE_URL,
                    supabasePublishableKey = AppConfig.SUPABASE_PUBLISHABLE_KEY,
                    tokenStore = secureTokenStore(appContext),
                    nowMs = { System.currentTimeMillis() },
                )
            supabaseAccountClient = created
            return created
        }
    }

    fun activeProfileStore(context: Context): ActiveProfileStore {
        activeProfileStore?.let { return it }
        synchronized(this) {
            activeProfileStore?.let { return it }
            val created = ActiveProfileStore(SharedPreferencesKeyValueStore(context.applicationContext, "supabase_sync_lab"))
            activeProfileStore = created
            return created
        }
    }

    fun syncProviderRepository(context: Context): SyncProviderRepository {
        return SyncProviderRepository(
            backendContextResolver = BackendContextResolverProvider.get(context.applicationContext),
            backendClient = BackendServicesProvider.backendClient(context.applicationContext),
        )
    }

    fun profileRepository(context: Context): ProfileRepository {
        return ProfileRepository(
            backendContextResolver = BackendContextResolverProvider.get(context.applicationContext),
            backendClient = BackendServicesProvider.backendClient(context.applicationContext),
        )
    }

    fun accountSettingsRepository(context: Context): AccountSettingsRepository {
        return AccountSettingsRepository(
            backendClient = BackendServicesProvider.backendClient(context.applicationContext),
        )
    }

    fun bootstrapRepository(context: Context): AccountBootstrapRepository {
        return DefaultAccountBootstrapRepository(
            clearImageCache = { clearImageCache(context.applicationContext) },
            supabase = accountClient(context.applicationContext),
            backendContextResolver = BackendContextResolverProvider.get(context.applicationContext),
            backendClient = BackendServicesProvider.backendClient(context.applicationContext),
            activeProfileStore = activeProfileStore(context.applicationContext),
            // Still the keystore implementation. The repository now names the port, so this
            // line is the one place that says which of the two it is, and it can change
            // without the repository noticing.
            tokenStore = secureTokenStore(context.applicationContext),
        )
    }

    fun pendingProviderAuthStore(context: Context): PendingProviderAuthStore {
        // The prefs file name is here rather than in commonMain because the file is an
        // Android concern; the key names are in `PendingProviderAuthStore` itself. Renaming
        // it would strand the in-flight OAuth state of every install that has one.
        return PendingProviderAuthStore(
            SharedPreferencesKeyValueStore(context.applicationContext, "pending_provider_auth"),
        )
    }

    fun homeCatalogService(context: Context): HomeCatalogService {
        homeCatalogService?.let { return it }
        synchronized(this) {
            homeCatalogService?.let { return it }
            val appContext = context.applicationContext
            // The cached type above is the `HomeCatalogService` interface; only the
            // construction names the `org.json`-pinned implementation.
            val created =
                CachingHomeCatalogService(
                    backendClient = BackendServicesProvider.backendClient(appContext),
                    backendContextResolver = BackendContextResolverProvider.get(appContext),
                    diskCacheStore = RecommendationCatalogDiskCacheStore(appContext, AndroidTimeSource()),
                )
            homeCatalogService = created
            return created
        }
    }

    fun createProfileDataCloudSync(
        context: Context,
    ): ProfileDataCloudSync {
        val appContext = context.applicationContext
        return ProfileDataCloudSync(
            context = appContext,
            supabase = accountClient(appContext),
            backend = BackendServicesProvider.backendClient(appContext),
            playbackSettings = PlaybackSettingsRepositoryProvider.get(appContext),
            activeProfileStore = activeProfileStore(appContext),
        )
    }

    fun createHouseholdAddonsCloudSync(
        context: Context,
        addonRegistry: MetadataAddonRegistry,
    ): HouseholdAddonsCloudSync {
        val appContext = context.applicationContext
        return HouseholdAddonsCloudSync(
            supabase = accountClient(appContext),
            backend = BackendServicesProvider.backendClient(appContext),
            addonRegistry = addonRegistry,
            activeProfileStore = activeProfileStore(appContext),
            logger = AndroidAppLogger(appContext),
            pluginSyncBridge = PluginSyncBridgeProvider.create(appContext, BackendServicesProvider.backendClient(appContext)),
        )
    }
}

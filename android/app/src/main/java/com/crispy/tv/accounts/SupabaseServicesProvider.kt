package com.crispy.tv.accounts

import android.content.Context
import com.crispy.tv.BuildConfig
import com.crispy.tv.backend.BackendContextResolverProvider
import com.crispy.tv.backend.BackendServicesProvider
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.home.RecommendationCatalogDiskCacheStore
import com.crispy.tv.home.HomeCatalogService
import com.crispy.tv.addons.registry.MetadataAddonRegistry
import com.crispy.tv.network.AppHttp
import com.crispy.tv.sync.HouseholdAddonsCloudSync
import com.crispy.tv.sync.ProfileDataCloudSync

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
                    appContext = appContext,
                    httpClient = AppHttp.client(appContext),
                    supabaseUrl = BuildConfig.SUPABASE_URL,
                    supabasePublishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
                    tokenStore = secureTokenStore(appContext),
                )
            supabaseAccountClient = created
            return created
        }
    }

    fun activeProfileStore(context: Context): ActiveProfileStore {
        activeProfileStore?.let { return it }
        synchronized(this) {
            activeProfileStore?.let { return it }
            val created = ActiveProfileStore(context.applicationContext)
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
        return AccountBootstrapRepository(
            appContext = context.applicationContext,
            supabase = accountClient(context.applicationContext),
            backendContextResolver = BackendContextResolverProvider.get(context.applicationContext),
            backendClient = BackendServicesProvider.backendClient(context.applicationContext),
            activeProfileStore = activeProfileStore(context.applicationContext),
            tokenStore = secureTokenStore(context.applicationContext),
        )
    }

    fun pendingProviderAuthStore(context: Context): PendingProviderAuthStore {
        return PendingProviderAuthStore(context.applicationContext)
    }

    fun homeCatalogService(context: Context): HomeCatalogService {
        homeCatalogService?.let { return it }
        synchronized(this) {
            homeCatalogService?.let { return it }
            val appContext = context.applicationContext
            val created =
                HomeCatalogService(
                    backendClient = BackendServicesProvider.backendClient(appContext),
                    backendContextResolver = BackendContextResolverProvider.get(appContext),
                    diskCacheStore = RecommendationCatalogDiskCacheStore(appContext),
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
            activeProfileStore = activeProfileStore(appContext),
        )
    }

    internal fun createHouseholdAddonsCloudSync(
        context: Context,
        addonRegistry: MetadataAddonRegistry,
    ): HouseholdAddonsCloudSync {
        val appContext = context.applicationContext
        return HouseholdAddonsCloudSync(
            supabase = accountClient(appContext),
            backend = BackendServicesProvider.backendClient(appContext),
            addonRegistry = addonRegistry,
            activeProfileStore = activeProfileStore(appContext),
        )
    }
}

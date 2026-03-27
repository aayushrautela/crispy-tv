package com.crispy.tv.accounts

import android.content.Context
import com.crispy.tv.BuildConfig
import com.crispy.tv.backend.BackendServicesProvider
import com.crispy.tv.home.HomeCatalogDiskCacheStore
import com.crispy.tv.home.HomeCatalogService
import com.crispy.tv.metadata.MetadataAddonRegistry
import com.crispy.tv.network.AppHttp
import com.crispy.tv.sync.HouseholdAddonsCloudSync
import com.crispy.tv.sync.ProfileDataCloudSync

object SupabaseServicesProvider {
    @Volatile
    private var supabaseAccountClient: SupabaseAccountClient? = null

    @Volatile
    private var activeProfileStore: ActiveProfileStore? = null

    @Volatile
    private var homeCatalogService: HomeCatalogService? = null

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

    fun homeCatalogService(context: Context): HomeCatalogService {
        homeCatalogService?.let { return it }
        synchronized(this) {
            homeCatalogService?.let { return it }
            val appContext = context.applicationContext
            val created =
                HomeCatalogService(
                    supabaseAccountClient = accountClient(appContext),
                    activeProfileStore = activeProfileStore(appContext),
                    backendClient = BackendServicesProvider.backendClient(appContext),
                    diskCacheStore = HomeCatalogDiskCacheStore(appContext),
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
        return HouseholdAddonsCloudSync(
            supabase = accountClient(context.applicationContext),
            addonRegistry = addonRegistry,
        )
    }
}

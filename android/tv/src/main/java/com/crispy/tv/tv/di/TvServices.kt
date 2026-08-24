package com.crispy.tv.tv.di

import android.content.Context
import com.crispy.tv.accounts.ActiveProfileStore
import com.crispy.tv.accounts.SupabaseAccountClient
import com.crispy.tv.addons.streams.AddonStreamsService
import com.crispy.tv.addons.streams.StreamResolver
import com.crispy.tv.backend.BackendContextResolver
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.network.AppHttp
import com.crispy.tv.tv.BuildConfig

object TvServices {
    @Volatile
    private var secureTokenStore: com.crispy.tv.accounts.SecureTokenStore? = null

    @Volatile
    private var accountClient: SupabaseAccountClient? = null

    @Volatile
    private var activeProfileStore: ActiveProfileStore? = null

    @Volatile
    private var backendClient: CrispyBackendClient? = null

    @Volatile
    private var contextResolver: BackendContextResolver? = null

    @Volatile
    private var streamResolver: StreamResolver? = null

    fun secureTokenStore(context: Context): com.crispy.tv.accounts.SecureTokenStore {
        secureTokenStore?.let { return it }
        synchronized(this) {
            secureTokenStore?.let { return it }
            val created = com.crispy.tv.accounts.SecureTokenStore(
                context.applicationContext,
            )
            secureTokenStore = created
            return created
        }
    }

    fun accountClient(context: Context): SupabaseAccountClient {
        accountClient?.let { return it }
        synchronized(this) {
            accountClient?.let { return it }
            val appContext = context.applicationContext
            val created = SupabaseAccountClient(
                appContext = appContext,
                httpClient = AppHttp.client(appContext),
                supabaseUrl = BuildConfig.SUPABASE_URL,
                supabasePublishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
                tokenStore = secureTokenStore(appContext),
            )
            accountClient = created
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

    fun backendClient(context: Context): CrispyBackendClient {
        backendClient?.let { return it }
        synchronized(this) {
            backendClient?.let { return it }
            val appContext = context.applicationContext
            val created = CrispyBackendClient(
                httpClient = AppHttp.client(appContext),
                backendUrl = BuildConfig.CRISPY_BACKEND_URL,
                aiHttpClient = AppHttp.aiClient(appContext),
            )
            backendClient = created
            return created
        }
    }

    fun contextResolver(context: Context): BackendContextResolver {
        contextResolver?.let { return it }
        synchronized(this) {
            contextResolver?.let { return it }
            val appContext = context.applicationContext
            val created = BackendContextResolver(
                supabaseAccountClient = accountClient(appContext),
                activeProfileStore = activeProfileStore(appContext),
                backendClient = backendClient(appContext),
            )
            contextResolver = created
            return created
        }
    }

    fun streamResolver(context: Context): StreamResolver {
        streamResolver?.let { return it }
        synchronized(this) {
            streamResolver?.let { return it }
            val appContext = context.applicationContext
            val created = StreamResolver(
                addonStreamsService = AddonStreamsService(
                    context = appContext,
                    addonManifestUrlsCsv = BuildConfig.METADATA_ADDON_URLS,
                    httpClient = AppHttp.client(appContext),
                ),
            )
            streamResolver = created
            return created
        }
    }

    suspend fun signOut(context: Context) {
        accountClient(context).signOut()
        contextResolver(context).clear()
    }
}

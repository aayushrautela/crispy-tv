package com.crispy.tv.backend

import android.content.Context
import com.crispy.tv.accounts.SupabaseServicesProvider

object BackendContextResolverProvider {
    @Volatile
    private var resolver: BackendContextResolver? = null

    fun get(context: Context): BackendContextResolver {
        resolver?.let { return it }
        synchronized(this) {
            resolver?.let { return it }
            val appContext = context.applicationContext
            val created = BackendContextResolver(
                supabaseAccountClient = SupabaseServicesProvider.accountClient(appContext),
                activeProfileStore = SupabaseServicesProvider.activeProfileStore(appContext),
                backendClient = BackendServicesProvider.backendClient(appContext),
            )
            resolver = created
            return created
        }
    }
}

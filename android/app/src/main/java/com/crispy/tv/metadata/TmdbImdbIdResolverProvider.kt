package com.crispy.tv.metadata

import android.content.Context
import com.crispy.tv.metadata.tmdb.TmdbServicesProvider

object TmdbImdbIdResolverProvider {
    @Volatile
    private var instance: TmdbImdbIdResolver? = null

    fun get(context: Context): TmdbImdbIdResolver {
        val existing = instance
        if (existing != null) {
            return existing
        }

        return synchronized(this) {
            val synchronizedExisting = instance
            if (synchronizedExisting != null) {
                synchronizedExisting
            } else {
                val appContext = context.applicationContext
                val identityService = TmdbServicesProvider.identityService(appContext)
                TmdbImdbIdResolver(identityService = identityService).also { created ->
                    instance = created
                }
            }
        }
    }
}

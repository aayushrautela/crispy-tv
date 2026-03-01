package com.crispy.tv.metadata

import android.content.Context
import com.crispy.tv.BuildConfig
import com.crispy.tv.network.AppHttp

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
                val httpClient = AppHttp.client(appContext)
                TmdbImdbIdResolver(apiKey = BuildConfig.TMDB_API_KEY, httpClient = httpClient).also { created ->
                    instance = created
                }
            }
        }
    }
}

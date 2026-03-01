package com.crispy.tv.metadata.tmdb

import android.content.Context
import com.crispy.tv.BuildConfig
import com.crispy.tv.network.AppHttp

object TmdbJsonClientProvider {
    @Volatile
    private var instance: TmdbJsonClient? = null

    fun get(context: Context): TmdbJsonClient {
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
                TmdbJsonClient(apiKey = BuildConfig.TMDB_API_KEY, httpClient = httpClient).also { created ->
                    instance = created
                }
            }
        }
    }
}

package com.crispy.tv.search

import android.content.Context
import com.crispy.tv.metadata.tmdb.TmdbJsonClientProvider

object TmdbSearchRepositoryProvider {
    @Volatile
    private var instance: TmdbSearchRepository? = null

    fun get(context: Context): TmdbSearchRepository {
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
                val tmdbClient = TmdbJsonClientProvider.get(appContext)
                TmdbSearchRepository(tmdbClient).also { created ->
                    instance = created
                }
            }
        }
    }
}

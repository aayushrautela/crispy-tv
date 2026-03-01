package com.crispy.tv.metadata.tmdb

import android.content.Context

object TmdbEnrichmentRepositoryProvider {
    @Volatile
    private var instance: TmdbEnrichmentRepository? = null

    fun get(context: Context): TmdbEnrichmentRepository {
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
                TmdbEnrichmentRepository(tmdbClient).also { created ->
                    instance = created
                }
            }
        }
    }
}

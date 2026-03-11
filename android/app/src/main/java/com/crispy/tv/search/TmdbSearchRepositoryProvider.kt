package com.crispy.tv.search

import android.content.Context
import com.crispy.tv.metadata.tmdb.TmdbServicesProvider

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
                val remoteDataSource = TmdbServicesProvider.searchRemoteDataSource(appContext)
                TmdbSearchRepository(remoteDataSource).also { created ->
                    instance = created
                }
            }
        }
    }
}

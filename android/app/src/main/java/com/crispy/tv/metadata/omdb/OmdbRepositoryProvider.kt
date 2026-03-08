package com.crispy.tv.metadata.omdb

import android.content.Context
import com.crispy.tv.network.AppHttp
import com.crispy.tv.settings.OmdbSettingsStore

object OmdbRepositoryProvider {
    @Volatile
    private var instance: OmdbRepository? = null

    fun get(context: Context): OmdbRepository {
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
                OmdbRepository(settingsStore = OmdbSettingsStore(appContext), httpClient = httpClient).also { created ->
                    instance = created
                }
            }
        }
    }
}

package com.crispy.tv.streams

import android.content.Context
import com.crispy.tv.network.AppHttp
import com.crispy.tv.plugins.streams.PluginStreamsServiceFactory

object PluginStreamLoaderProvider {
    @Volatile
    private var instance: PluginStreamLoader? = null

    fun get(context: Context): PluginStreamLoader {
        val existing = instance
        if (existing != null) {
            return existing
        }
        return synchronized(this) {
            val synchronizedExisting = instance
            if (synchronizedExisting != null) {
                synchronizedExisting
            } else {
                create(context.applicationContext).also { created -> instance = created }
            }
        }
    }

    private fun create(appContext: Context): PluginStreamLoader {
        val source = PluginStreamsServiceFactory.create(appContext, AppHttp.okHttp(appContext))
        return PluginStreamLoader { request ->
            source.load(
                mediaType = request.mediaType,
                lookupId = request.lookupId,
                title = request.title.orEmpty(),
                year = request.year.toYearInt(),
                season = request.season,
                episode = request.episode,
                onProvidersResolved = null,
                onProviderResult = null,
            )
        }
    }

    private fun String?.toYearInt(): Int? =
        this?.trim()?.take(4)?.toIntOrNull()?.takeIf { it > 1800 }
}

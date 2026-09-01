package com.crispy.tv.streams

import android.content.Context
import com.crispy.tv.BuildConfig
import com.crispy.tv.addons.streams.AddonStreamsService
import com.crispy.tv.addons.streams.StreamResolver
import com.crispy.tv.network.AppHttp

/**
 * App-side wiring for the shared addons module. Keeps the historical call-site API
 * (`StreamResolverProvider.get(context)`) while construction now lives in this app.
 */
object StreamResolverProvider {
    @Volatile
    private var instance: StreamResolver? = null

    fun get(context: Context): StreamResolver {
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

    private fun create(appContext: Context): StreamResolver {
        val addonStreamsService =
            AddonStreamsService(
                context = appContext,
                httpClient = AppHttp.client(appContext),
            )
        return StreamResolver(addonStreamsService)
    }
}

package com.crispy.tv.streams

import android.content.Context
import com.crispy.tv.addons.registry.metadataAddonRegistry
import com.crispy.tv.addons.streams.AddonStreamsService
import com.crispy.tv.addons.streams.CachingStreamResolver
import com.crispy.tv.addons.streams.StreamResolver
import com.crispy.tv.network.AppHttp
import com.crispy.tv.platform.android.AndroidAppLogger
import kotlinx.coroutines.Dispatchers

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
                addonRegistry = metadataAddonRegistry(appContext),
                httpClient = AppHttp.client(appContext),
                logger = AndroidAppLogger(appContext),
                ioDispatcher = Dispatchers.IO,
            )
        return CachingStreamResolver(
            addonStreamsLoader = addonStreamsService,
            logger = AndroidAppLogger(appContext),
            nowMs = System::currentTimeMillis,
        )
    }
}

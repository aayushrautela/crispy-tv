package com.crispy.tv

import android.content.Context
import com.crispy.tv.metadata.RemoteCatalogSearchLabService
import com.crispy.tv.introskip.IntroSkipService
import com.crispy.tv.introskip.RemoteIntroSkipService
import com.crispy.tv.metadata.RemoteMetadataLabDataSource
import com.crispy.tv.metadata.RemoteSupabaseSyncLabService
import com.crispy.tv.watchhistory.RemoteWatchHistoryService
import com.crispy.tv.watchhistory.WatchHistoryConfig
import com.crispy.tv.network.AppHttp
import com.crispy.tv.nativeengine.playback.NativePlaybackController
import com.crispy.tv.nativeengine.playback.NativePlaybackEvent
import com.crispy.tv.nativeengine.playback.PlaybackController
import com.crispy.tv.nativeengine.torrent.TorrentEngineClient
import com.crispy.tv.player.CatalogSearchLabService
import com.crispy.tv.player.CoreDomainMetadataLabResolver
import com.crispy.tv.player.MetadataLabResolver
import com.crispy.tv.player.SupabaseSyncLabService
import com.crispy.tv.player.WatchHistoryService

interface TorrentResolver {
    suspend fun resolveStreamUrl(magnetLink: String, sessionId: String): String
    fun stopAndClear()
    fun close()
}

private class NativeTorrentResolver(context: Context) : TorrentResolver {
    private val client = TorrentEngineClient(context)

    override suspend fun resolveStreamUrl(magnetLink: String, sessionId: String): String {
        return client.startTorrentAndResolveStreamUrl(magnetLink = magnetLink, sessionId = sessionId)
    }

    override fun stopAndClear() {
        client.stopAllIfConnected(clearStorage = true)
    }

    override fun close() {
        client.close()
    }
}

private fun newMetadataResolver(context: Context): MetadataLabResolver {
    val appContext = context.applicationContext
    return CoreDomainMetadataLabResolver(
        RemoteMetadataLabDataSource(
            context = appContext,
            addonManifestUrlsCsv = BuildConfig.METADATA_ADDON_URLS,
            tmdbApiKey = BuildConfig.TMDB_API_KEY,
            httpClient = AppHttp.client(appContext),
        )
    )
}

private fun newCatalogSearchService(context: Context): CatalogSearchLabService {
    val appContext = context.applicationContext
    return RemoteCatalogSearchLabService(
        context = appContext,
        addonManifestUrlsCsv = BuildConfig.METADATA_ADDON_URLS,
        httpClient = AppHttp.client(appContext),
    )
}

private fun newWatchHistoryService(context: Context): WatchHistoryService {
    val appContext = context.applicationContext
    return RemoteWatchHistoryService(
        context = appContext,
        httpClient = AppHttp.client(appContext),
        traktClientId = BuildConfig.TRAKT_CLIENT_ID,
        simklClientId = BuildConfig.SIMKL_CLIENT_ID,
        config =
            WatchHistoryConfig(
                traktClientSecret = BuildConfig.TRAKT_CLIENT_SECRET,
                traktRedirectUri = BuildConfig.TRAKT_REDIRECT_URI,
                simklClientSecret = BuildConfig.SIMKL_CLIENT_SECRET,
                simklRedirectUri = BuildConfig.SIMKL_REDIRECT_URI,
                appVersion = BuildConfig.VERSION_NAME,
            )
    )
}

private fun newSupabaseSyncService(
    context: Context,
    watchHistoryService: WatchHistoryService
): SupabaseSyncLabService {
    val appContext = context.applicationContext
    return RemoteSupabaseSyncLabService(
        context = appContext,
        httpClient = AppHttp.client(appContext),
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY,
        addonManifestUrlsCsv = BuildConfig.METADATA_ADDON_URLS,
        watchHistoryService = watchHistoryService
    )
}

object PlaybackDependencies {
    @Volatile
    var playbackControllerFactory: (Context, (NativePlaybackEvent) -> Unit) -> PlaybackController =
        { context, callback ->
            NativePlaybackController(context, callback)
        }

    @Volatile
    var torrentResolverFactory: (Context) -> TorrentResolver = { context ->
        NativeTorrentResolver(context)
    }

    @Volatile
    var metadataResolverFactory: (Context) -> MetadataLabResolver = { context ->
        newMetadataResolver(context)
    }

    @Volatile
    var catalogSearchServiceFactory: (Context) -> CatalogSearchLabService = { context ->
        newCatalogSearchService(context)
    }

    @Volatile
    var watchHistoryServiceFactory: (Context) -> WatchHistoryService = { context ->
        newWatchHistoryService(context)
    }

    @Volatile
    var supabaseSyncServiceFactory: (Context, WatchHistoryService) -> SupabaseSyncLabService = { context, watchHistoryService ->
        newSupabaseSyncService(
            context = context,
            watchHistoryService = watchHistoryService
        )
    }

    @Volatile
    var introSkipServiceFactory: (Context) -> IntroSkipService = { context ->
        val appContext = context.applicationContext
        RemoteIntroSkipService(
            httpClient = AppHttp.client(appContext),
            introDbBaseUrl = BuildConfig.INTRODB_API_URL,
        )
    }

    fun reset() {
        playbackControllerFactory = { context, callback ->
            NativePlaybackController(context, callback)
        }
        torrentResolverFactory = { context -> NativeTorrentResolver(context) }
        metadataResolverFactory = { context ->
            newMetadataResolver(context)
        }
        catalogSearchServiceFactory = { context ->
            newCatalogSearchService(context)
        }
        watchHistoryServiceFactory = { context ->
            newWatchHistoryService(context)
        }
        supabaseSyncServiceFactory = { context, watchHistoryService ->
            newSupabaseSyncService(
                context = context,
                watchHistoryService = watchHistoryService
            )
        }
        introSkipServiceFactory = { context ->
            val appContext = context.applicationContext
            RemoteIntroSkipService(
                httpClient = AppHttp.client(appContext),
                introDbBaseUrl = BuildConfig.INTRODB_API_URL,
            )
        }
    }
}

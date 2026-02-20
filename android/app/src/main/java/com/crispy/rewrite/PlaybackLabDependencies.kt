package com.crispy.rewrite

import android.content.Context
import com.crispy.rewrite.metadata.RemoteCatalogSearchLabService
import com.crispy.rewrite.introskip.IntroSkipService
import com.crispy.rewrite.introskip.RemoteIntroSkipService
import com.crispy.rewrite.metadata.RemoteMetadataLabDataSource
import com.crispy.rewrite.metadata.RemoteSupabaseSyncLabService
import com.crispy.rewrite.metadata.RemoteWatchHistoryLabService
import com.crispy.rewrite.nativeengine.playback.NativePlaybackController
import com.crispy.rewrite.nativeengine.playback.NativePlaybackEvent
import com.crispy.rewrite.nativeengine.playback.PlaybackController
import com.crispy.rewrite.nativeengine.torrent.TorrentEngineClient
import com.crispy.rewrite.player.CatalogSearchLabService
import com.crispy.rewrite.player.CoreDomainMetadataLabResolver
import com.crispy.rewrite.player.MetadataLabResolver
import com.crispy.rewrite.player.SupabaseSyncLabService
import com.crispy.rewrite.player.WatchHistoryLabService

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
    return CoreDomainMetadataLabResolver(
        RemoteMetadataLabDataSource(
            context = context,
            addonManifestUrlsCsv = BuildConfig.METADATA_ADDON_URLS,
            tmdbApiKey = BuildConfig.TMDB_API_KEY
        )
    )
}

private fun newCatalogSearchService(context: Context): CatalogSearchLabService {
    return RemoteCatalogSearchLabService(
        context = context,
        addonManifestUrlsCsv = BuildConfig.METADATA_ADDON_URLS
    )
}

private fun newWatchHistoryService(context: Context): WatchHistoryLabService {
    return RemoteWatchHistoryLabService(
        context = context,
        traktClientId = BuildConfig.TRAKT_CLIENT_ID,
        simklClientId = BuildConfig.SIMKL_CLIENT_ID
    )
}

private fun newSupabaseSyncService(
    context: Context,
    watchHistoryService: WatchHistoryLabService
): SupabaseSyncLabService {
    return RemoteSupabaseSyncLabService(
        context = context,
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY,
        addonManifestUrlsCsv = BuildConfig.METADATA_ADDON_URLS,
        watchHistoryService = watchHistoryService
    )
}

object PlaybackLabDependencies {
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
    var watchHistoryServiceFactory: (Context) -> WatchHistoryLabService = { context ->
        newWatchHistoryService(context)
    }

    @Volatile
    var supabaseSyncServiceFactory: (Context, WatchHistoryLabService) -> SupabaseSyncLabService = { context, watchHistoryService ->
        newSupabaseSyncService(
            context = context,
            watchHistoryService = watchHistoryService
        )
    }

    @Volatile
    var introSkipServiceFactory: (Context) -> IntroSkipService = {
        RemoteIntroSkipService(introDbBaseUrl = BuildConfig.INTRODB_API_URL)
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
        introSkipServiceFactory = {
            RemoteIntroSkipService(introDbBaseUrl = BuildConfig.INTRODB_API_URL)
        }
    }
}

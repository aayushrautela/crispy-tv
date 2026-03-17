package com.crispy.tv

import android.content.Context
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.metadata.TmdbEpisodeListProvider
import com.crispy.tv.introskip.IntroSkipService
import com.crispy.tv.introskip.RemoteIntroSkipService
import com.crispy.tv.metadata.RemoteMetadataLabDataSource
import com.crispy.tv.metadata.RemoteSupabaseSyncLabService
import com.crispy.tv.metadata.tmdb.TmdbServicesProvider
import com.crispy.tv.watchhistory.RemoteWatchHistoryService
import com.crispy.tv.watchhistory.WatchHistoryConfig
import com.crispy.tv.network.AppHttp
import com.crispy.tv.nativeengine.playback.NativePlaybackController
import com.crispy.tv.nativeengine.playback.PlaybackController
import com.crispy.tv.nativeengine.torrent.TorrentEngineClient
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
    val tmdbRepository = TmdbServicesProvider.metadataRecordRepository(appContext)
    return CoreDomainMetadataLabResolver(
        RemoteMetadataLabDataSource(
            context = appContext,
            addonManifestUrlsCsv = BuildConfig.METADATA_ADDON_URLS,
            tmdbRepository = tmdbRepository,
            httpClient = AppHttp.client(appContext),
        )
    )
}

private fun newWatchHistoryService(context: Context): WatchHistoryService {
    val appContext = context.applicationContext
    val httpClient = AppHttp.client(appContext)
    val tmdbEnrichmentRepository = TmdbServicesProvider.enrichmentRepository(appContext)
    val episodeListProvider = TmdbEpisodeListProvider(
        tmdbEnrichmentRepository = tmdbEnrichmentRepository,
    )
    lateinit var service: RemoteWatchHistoryService
    service = RemoteWatchHistoryService(
        context = appContext,
        httpClient = httpClient,
        traktClientId = BuildConfig.TRAKT_CLIENT_ID,
        simklClientId = BuildConfig.SIMKL_CLIENT_ID,
        episodeListProvider = episodeListProvider,
        config =
            WatchHistoryConfig(
                traktClientSecret = BuildConfig.TRAKT_CLIENT_SECRET,
                traktRedirectUri = BuildConfig.TRAKT_REDIRECT_URI,
                simklClientSecret = BuildConfig.SIMKL_CLIENT_SECRET,
                simklRedirectUri = BuildConfig.SIMKL_REDIRECT_URI,
                appVersion = BuildConfig.VERSION_NAME,
            ),
        onTraktTokenExpired = {
            try {
                val cloudSync =
                    SupabaseServicesProvider.createProfileDataCloudSync(
                        context = appContext,
                        watchHistoryService = service,
                    )
                cloudSync.pullForActiveProfile()
                service.authState().traktSession?.accessToken
            } catch (_: Exception) {
                null
            }
        },
    )
    return service
}

@Suppress("UNUSED_PARAMETER")
private fun newSupabaseSyncService(
    context: Context,
    watchHistoryService: WatchHistoryService
): SupabaseSyncLabService {
    val appContext = context.applicationContext
    return RemoteSupabaseSyncLabService(
        context = appContext,
        supabase = SupabaseServicesProvider.accountClient(appContext),
        addonManifestUrlsCsv = BuildConfig.METADATA_ADDON_URLS,
    )
}

object PlaybackDependencies {
    @Volatile
    var playbackControllerFactory: (Context) -> PlaybackController =
        { context ->
            NativePlaybackController(context)
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
        playbackControllerFactory = { context ->
            NativePlaybackController(context)
        }
        torrentResolverFactory = { context -> NativeTorrentResolver(context) }
        metadataResolverFactory = { context ->
            newMetadataResolver(context)
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

package com.crispy.tv

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.audio.AudioFocusManager
import com.crispy.tv.backend.BackendContextResolverProvider
import com.crispy.tv.backend.BackendServicesProvider
import com.crispy.tv.metadata.BackendEpisodeListProvider
import com.crispy.tv.introskip.IntroSkipService
import com.crispy.tv.introskip.RemoteIntroSkipService
import com.crispy.tv.metadata.RemoteMetadataLabDataSource
import com.crispy.tv.metadata.RemoteSupabaseSyncLabService
import com.crispy.tv.network.AppHttp
import com.crispy.tv.streams.StreamResolver
import com.crispy.tv.streams.StreamResolverProvider
import com.crispy.tv.watchhistory.BackendWatchHistoryService
import com.crispy.tv.watchhistory.WatchHistoryConfig
import com.crispy.tv.nativeengine.playback.LibassRenderType
import com.crispy.tv.nativeengine.playback.NativePlaybackController
import com.crispy.tv.nativeengine.playback.PlaybackController
import com.crispy.tv.nativeengine.torrent.TorrentEngineClient
import com.crispy.tv.player.CoreDomainMetadataLabResolver
import com.crispy.tv.player.EpisodeListProvider
import com.crispy.tv.player.MetadataLabResolver
import com.crispy.tv.player.SupabaseSyncLabService
import com.crispy.tv.player.WatchHistoryService
import com.crispy.tv.settings.PlaybackSettingsRepositoryProvider

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
            httpClient = AppHttp.client(appContext),
        )
    )
}

private fun newWatchHistoryService(context: Context): WatchHistoryService {
    val appContext = context.applicationContext
    val episodeListProvider = BackendEpisodeListProvider(
        supabaseAccountClient = SupabaseServicesProvider.accountClient(appContext),
        backendClient = BackendServicesProvider.backendClient(appContext),
    )
    return BackendWatchHistoryService(
        context = appContext,
        backend = BackendServicesProvider.backendClient(appContext),
        backendContextResolver = BackendContextResolverProvider.get(appContext),
        episodeListProvider = episodeListProvider,
        config =
            WatchHistoryConfig(
                appVersion = BuildConfig.VERSION_NAME,
            ),
    )
}

private fun newEpisodeListProvider(context: Context): EpisodeListProvider {
    val appContext = context.applicationContext
    return BackendEpisodeListProvider(
        supabaseAccountClient = SupabaseServicesProvider.accountClient(appContext),
        backendClient = BackendServicesProvider.backendClient(appContext),
    )
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

@OptIn(UnstableApi::class)
object PlaybackDependencies {
    @Volatile
    var playbackControllerFactory: (Context) -> PlaybackController =
        { context ->
            val settings = PlaybackSettingsRepositoryProvider.get(context).settings.value
            NativePlaybackController(
                context = context,
                useLibass = settings.useLibass,
                libassRenderType = LibassRenderType.fromName(settings.libassRenderType),
            )
        }

    @Volatile
    var torrentResolverFactory: (Context) -> TorrentResolver = { context ->
        NativeTorrentResolver(context)
    }

    @Volatile
    private var torrentResolverInstance: TorrentResolver? = null

    fun getTorrentResolver(context: Context): TorrentResolver {
        val existing = torrentResolverInstance
        if (existing != null) {
            return existing
        }
        return synchronized(this) {
            torrentResolverInstance ?: run {
                val created = torrentResolverFactory(context.applicationContext)
                torrentResolverInstance = created
                created
            }
        }
    }

    fun resetTorrentResolver() {
        torrentResolverInstance?.close()
        torrentResolverInstance = null
    }

    @Volatile
    private var audioFocusManagerInstance: AudioFocusManager? = null

    fun getAudioFocusManager(context: Context): AudioFocusManager {
        val existing = audioFocusManagerInstance
        if (existing != null) {
            return existing
        }
        return synchronized(this) {
            audioFocusManagerInstance ?: run {
                val created = AudioFocusManager(context.applicationContext)
                audioFocusManagerInstance = created
                created
            }
        }
    }

    fun resetAudioFocusManager() {
        audioFocusManagerInstance = null
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

    @Volatile
    var streamResolverFactory: (Context) -> StreamResolver = { context ->
        StreamResolverProvider.get(context)
    }

    @Volatile
    var episodeListProviderFactory: (Context) -> EpisodeListProvider = { context ->
        newEpisodeListProvider(context)
    }

    fun reset() {
        playbackControllerFactory = { context ->
            val settings = PlaybackSettingsRepositoryProvider.get(context).settings.value
            NativePlaybackController(
                context = context,
                useLibass = settings.useLibass,
                libassRenderType = LibassRenderType.fromName(settings.libassRenderType),
            )
        }
        torrentResolverFactory = { context -> NativeTorrentResolver(context) }
        resetTorrentResolver()
        resetAudioFocusManager()
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
        streamResolverFactory = { context -> StreamResolverProvider.get(context) }
        episodeListProviderFactory = { context -> newEpisodeListProvider(context) }
    }
}

package com.crispy.tv

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.distribution.AppDistribution
import com.crispy.tv.audio.AudioFocusManager
import com.crispy.tv.backend.BackendContextResolverProvider
import com.crispy.tv.backend.BackendServicesProvider
import com.crispy.tv.platform.AppConfig
import com.crispy.tv.addons.sources.BackendEpisodeListProvider
import com.crispy.tv.introskip.IntroSkipService
import com.crispy.tv.introskip.RemoteIntroSkipService
import com.crispy.tv.addons.sources.RemoteMetadataLabDataSource
import com.crispy.tv.addons.sources.RemoteSupabaseSyncLabService
import com.crispy.tv.network.AppHttp
import com.crispy.tv.addons.streams.StreamResolver
import com.crispy.tv.streams.StreamResolverProvider
import com.crispy.tv.watchhistory.BackendWatchHistoryService
import com.crispy.tv.watchhistory.WatchHistoryConfig
import com.crispy.tv.nativeengine.playback.LibassRenderType
import com.crispy.tv.nativeengine.playback.NativePlaybackController
import com.crispy.tv.nativeengine.playback.PlaybackController
import com.crispy.tv.player.CoreDomainMetadataLabResolver
import com.crispy.tv.player.EpisodeListProvider
import com.crispy.tv.player.MetadataLabResolver
import com.crispy.tv.player.SupabaseSyncLabService
import com.crispy.tv.player.TorrentResolver
import com.crispy.tv.player.TorrentSupportUnavailableException
import com.crispy.tv.player.WatchHistoryService
import com.crispy.tv.settings.PlaybackSettingsRepositoryProvider

private fun newMetadataResolver(context: Context): MetadataLabResolver {
    val appContext = context.applicationContext
    return CoreDomainMetadataLabResolver(
        RemoteMetadataLabDataSource(
            context = appContext,
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
                appVersion = AppConfig.VERSION_NAME,
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

    /**
     * Store builds: the torrent engine module is not on the classpath, so every
     * magnet link fails fast instead of silently doing nothing.
     */
    private class UnavailableTorrentResolver : TorrentResolver {
        override suspend fun resolveStreamUrl(magnetLink: String, sessionId: String): String {
            throw TorrentSupportUnavailableException()
        }

        override fun stopAndClear() = Unit

        override fun close() = Unit
    }

    @Volatile
    var torrentResolverFactory: (Context) -> TorrentResolver = { UnavailableTorrentResolver() }

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
            introDbBaseUrl = AppConfig.INTRODB_API_URL,
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
        AppDistribution.current.installTorrentResolver(this)
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
                introDbBaseUrl = AppConfig.INTRODB_API_URL,
            )
        }
        streamResolverFactory = { context -> StreamResolverProvider.get(context) }
        episodeListProviderFactory = { context -> newEpisodeListProvider(context) }
    }
}

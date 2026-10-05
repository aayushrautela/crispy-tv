package com.crispy.tv

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.crispy.tv.app.appGraph
import com.crispy.tv.audio.AudioFocusManager
import com.crispy.tv.platform.AppConfig
import com.crispy.tv.platform.AppLogger
import com.crispy.tv.platform.android.AndroidAppLogger
import com.crispy.tv.addons.sources.BackendEpisodeListProvider
import com.crispy.tv.introskip.IntroSkipService
import com.crispy.tv.introskip.RemoteIntroSkipService
import com.crispy.tv.addons.registry.metadataAddonRegistry
import com.crispy.tv.addons.sources.RemoteMetadataLabDataSource
import com.crispy.tv.addons.sources.RemoteSupabaseSyncLabService
import com.crispy.tv.network.AppHttp
import com.crispy.tv.addons.streams.StreamResolver
import com.crispy.tv.streams.StreamResolverProvider
import com.crispy.tv.nativeengine.playback.LibassRenderType
import com.crispy.tv.nativeengine.playback.NativePlaybackController
import com.crispy.tv.nativeengine.playback.PlaybackController
import com.crispy.tv.player.CoreDomainMetadataLabResolver
import com.crispy.tv.player.EpisodeListProvider
import com.crispy.tv.player.MetadataLabResolver
import com.crispy.tv.player.SupabaseSyncLabService
import com.crispy.tv.player.TorrentResolver
import com.crispy.tv.player.TorrentSupportUnavailableException
import kotlinx.coroutines.Dispatchers

private fun newMetadataResolver(context: Context): MetadataLabResolver {
    val appContext = context.applicationContext
    return CoreDomainMetadataLabResolver(
        RemoteMetadataLabDataSource(
            addonRegistry = metadataAddonRegistry(appContext),
            httpClient = AppHttp.client(appContext),
            ioDispatcher = Dispatchers.IO,
        )
    )
}

private fun newEpisodeListProvider(context: Context): EpisodeListProvider {
    val appContext = context.applicationContext
    val graph = appContext.appGraph().graph
    return BackendEpisodeListProvider(
        supabaseAccountClient = graph.accountClient,
        backendClient = graph.backendClient,
    )
}

/**
 * `RemoteSupabaseSyncLabService` used to take a `Context` and this used to take a
 * `WatchHistoryService`, and **neither was ever read** -- both carried
 * `@Suppress("UNUSED_PARAMETER")` at the far end of the chain. A parameter no body
 * consults is not a dependency, and both were holding one `commonMain` file in
 * `androidMain` for nothing: the `Context` in particular was the *whole* pin, so
 * deleting it is what moved the file rather than working around it.
 *
 * The factory hook lost its `WatchHistoryService` parameter for the same reason.
 * That hook is read and written nowhere outside this file, so nothing outside it
 * had to change -- and keeping the parameter would have only moved the dead
 * argument up one level rather than removing it.
 */
private fun newSupabaseSyncService(context: Context): SupabaseSyncLabService {
    val appContext = context.applicationContext
    return RemoteSupabaseSyncLabService(
        supabase = appContext.appGraph().graph.accountClient,
        ioDispatcher = Dispatchers.IO,
    )
}

/**
 * The seams a distribution can install a different implementation behind.
 *
 * That is what every member here is now, and it is a narrower claim than this object used to
 * make. `watchHistoryServiceFactory` was here too, on the stated grounds that `:androidApp`'s
 * `store` and `sideload` variants install into it. **Nothing does.** The only assignments to any
 * member of this object in the whole tree are `DistributionComponents`, which writes
 * `torrentResolverFactory` -- from inside `:app`, not from a flavour -- and one test. Watch
 * history had one answer and a `@Volatile` in front of it, and the cost of that was that a
 * desktop could not reach the answer without writing into an `androidMain` global. It is a
 * `by lazy` member of `AppGraph` now, named on the graph.
 *
 * What remains is the player and the plugins: a playback controller, a torrent resolver, audio
 * focus, the metadata and intro-skip services, and the stream resolver. Each still needs a
 * `Context`, and each is `androidMain` code today.
 */
@OptIn(UnstableApi::class)
object PlaybackDependencies {
    @Volatile
    var playbackControllerFactory: (Context) -> PlaybackController =
        { context ->
            val settings = context.appGraph().graph.playbackSettingsRepository.settings.value
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

    @Volatile
    var metadataResolverFactory: (Context) -> MetadataLabResolver = { context ->
        newMetadataResolver(context)
    }

    @Volatile
    var supabaseSyncServiceFactory: (Context) -> SupabaseSyncLabService = { context ->
        newSupabaseSyncService(context = context)
    }

    @Volatile
    var introSkipServiceFactory: (Context) -> IntroSkipService = { context ->
        val appContext = context.applicationContext
        RemoteIntroSkipService(
            httpClient = AppHttp.client(appContext),
            logger = AndroidAppLogger(appContext),
            nowMs = { System.currentTimeMillis() },
            ioDispatcher = Dispatchers.IO,
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
}

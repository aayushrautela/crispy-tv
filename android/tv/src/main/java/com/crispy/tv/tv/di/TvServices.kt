package com.crispy.tv.tv.di

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.crispy.tv.accounts.ActiveProfileStore
import com.crispy.tv.accounts.SupabaseAccountClient
import com.crispy.tv.addons.registry.metadataAddonRegistry
import com.crispy.tv.addons.sources.BackendEpisodeListProvider
import com.crispy.tv.addons.streams.AddonStreamsService
import com.crispy.tv.addons.streams.CachingStreamResolver
import com.crispy.tv.addons.streams.StreamResolver
import com.crispy.tv.watchhistory.BackendWatchHistoryService
import com.crispy.tv.backend.BackendContextResolver
import com.crispy.tv.backend.CachingBackendContextResolver
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.network.AppHttp
import com.crispy.tv.nativeengine.playback.NativePlaybackController
import com.crispy.tv.platform.android.AndroidAppLogger
import com.crispy.tv.platform.android.AndroidMonotonicClock
import com.crispy.tv.platform.android.AndroidTimeSource
import com.crispy.tv.platform.android.SharedPreferencesKeyValueStore
import com.crispy.tv.player.TorrentResolver
import com.crispy.tv.torrentengine.NativeTorrentResolver
import com.crispy.tv.tv.BuildConfig
import com.crispy.tv.tv.player.TvPlaybackSelectionStore
import kotlinx.coroutines.Dispatchers

object TvServices {
    @Volatile
    private var secureTokenStore: com.crispy.tv.accounts.SecureTokenStore? = null

    @Volatile
    private var accountClient: SupabaseAccountClient? = null

    @Volatile
    private var activeProfileStore: ActiveProfileStore? = null

    @Volatile
    private var backendClient: CrispyBackendClient? = null

    @Volatile
    private var contextResolver: BackendContextResolver? = null

    @Volatile
    private var streamResolver: StreamResolver? = null

    @Volatile
    private var watchHistoryService: BackendWatchHistoryService? = null

    @Volatile
    private var playbackSelectionStore: TvPlaybackSelectionStore? = null

    @Volatile
    private var torrentResolver: TorrentResolver? = null

    fun secureTokenStore(context: Context): com.crispy.tv.accounts.SecureTokenStore {
        secureTokenStore?.let { return it }
        synchronized(this) {
            secureTokenStore?.let { return it }
            val created = com.crispy.tv.accounts.SecureTokenStore(
                context.applicationContext,
            )
            secureTokenStore = created
            return created
        }
    }

    fun accountClient(context: Context): SupabaseAccountClient {
        accountClient?.let { return it }
        synchronized(this) {
            accountClient?.let { return it }
            val appContext = context.applicationContext
            val created = SupabaseAccountClient(
                httpClient = AppHttp.client(appContext),
                supabaseUrl = BuildConfig.SUPABASE_URL,
                supabasePublishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
                tokenStore = secureTokenStore(appContext),
                    nowMs = { System.currentTimeMillis() },
            )
            accountClient = created
            return created
        }
    }

    fun activeProfileStore(context: Context): ActiveProfileStore {
        activeProfileStore?.let { return it }
        synchronized(this) {
            activeProfileStore?.let { return it }
            val created = ActiveProfileStore(SharedPreferencesKeyValueStore(context.applicationContext, "supabase_sync_lab"))
            activeProfileStore = created
            return created
        }
    }

    fun backendClient(context: Context): CrispyBackendClient {
        backendClient?.let { return it }
        synchronized(this) {
            backendClient?.let { return it }
            val appContext = context.applicationContext
            val created = CrispyBackendClient(
                httpClient = AppHttp.client(appContext),
                backendUrl = BuildConfig.CRISPY_BACKEND_URL,
                aiHttpClient = AppHttp.aiClient(appContext),
            )
            backendClient = created
            return created
        }
    }

    fun contextResolver(context: Context): BackendContextResolver {
        contextResolver?.let { return it }
        synchronized(this) {
            contextResolver?.let { return it }
            val appContext = context.applicationContext
            val created = CachingBackendContextResolver(
                supabaseAccountClient = accountClient(appContext),
                activeProfileStore = activeProfileStore(appContext),
                backendClient = backendClient(appContext),
            )
            contextResolver = created
            return created
        }
    }

    fun streamResolver(context: Context): StreamResolver {
        streamResolver?.let { return it }
        synchronized(this) {
            streamResolver?.let { return it }
            val appContext = context.applicationContext
            val created = CachingStreamResolver(
                addonStreamsLoader =
                    AddonStreamsService(
                        addonRegistry = metadataAddonRegistry(appContext),
                        httpClient = AppHttp.client(appContext),
                        logger = AndroidAppLogger(appContext),
                        ioDispatcher = Dispatchers.IO,
                    ),
                logger = AndroidAppLogger(appContext),
                nowMs = System::currentTimeMillis,
            )
            streamResolver = created
            return created
        }
    }

    fun watchHistoryService(context: Context): BackendWatchHistoryService {
        watchHistoryService?.let { return it }
        synchronized(this) {
            watchHistoryService?.let { return it }
            val appContext = context.applicationContext
            val created = BackendWatchHistoryService(
                progressStore = SharedPreferencesKeyValueStore(appContext, "watch_progress"),
                timeSource = AndroidTimeSource(),
                monotonicClock = AndroidMonotonicClock(),
                logger = AndroidAppLogger(appContext),
                backend = backendClient(appContext),
                backendContextResolver = contextResolver(appContext),
                episodeListProvider = BackendEpisodeListProvider(
                    supabaseAccountClient = accountClient(appContext),
                    backendClient = backendClient(appContext),
                ),
            )
            watchHistoryService = created
            return created
        }
    }

    /**
     * The torrent engine.
     *
     * **The TV app is a sideload-only surface**, the same posture it already takes
     * with the YouTube extractor: it depends on the engines directly rather than
     * through the phone's distribution seam. So the engine is here, and a magnet link
     * resolves the way it resolves on the phone -- through the shared rule in
     * `com.crispy.tv.addons.playback.resolvePlaybackSource`.
     *
     * A store TV build would need the phone's `distribution` flavour axis so that
     * this module, and the YouTube extractor beside it, are excluded structurally
     * rather than by a runtime check. That is release work, not playback work.
     */
    fun torrentResolver(context: Context): TorrentResolver {
        torrentResolver?.let { return it }
        synchronized(this) {
            torrentResolver?.let { return it }
            val created = NativeTorrentResolver(context.applicationContext)
            torrentResolver = created
            return created
        }
    }

    suspend fun signOut(context: Context) {
        accountClient(context).signOut()
        secureTokenStore(context).clear()
        contextResolver(context).clear()
    }

    /**
     * The table of stream picks waiting to be played.
     *
     * Process-scoped on purpose: a pick lives for as long as it takes the user to walk
     * from the source picker to the player, and it dies with the process, because a
     * pick is a choice the viewer made in this sitting -- not state worth restoring
     * after a reboot.
     */
    fun playbackSelectionStore(context: Context): TvPlaybackSelectionStore {
        playbackSelectionStore?.let { return it }
        synchronized(this) {
            playbackSelectionStore?.let { return it }
            val created = TvPlaybackSelectionStore()
            playbackSelectionStore = created
            return created
        }
    }

    /**
     * A fresh playback engine for one playback session.
     *
     * **A new controller per session, not a shared one**: the controller owns an
     * `ExoPlayer`, a libmpv runtime and a sidecar controller, and `release()` on any
     * of them ends the session for whoever holds it. The phone gets the same rule for
     * the same reason -- `PlaybackDependencies.playbackControllerFactory` is a factory
     * for one session's controller.
     *
     * Libass is off here, as it is by default on the phone: a TV box is the device
     * least likely to be missing a codec, and the libass overlay path is the one with
     * a surface of its own to keep in step with the video surface.
     */
    @OptIn(UnstableApi::class)
    fun newPlaybackController(context: Context): NativePlaybackController {
        return NativePlaybackController(context.applicationContext)
    }
}

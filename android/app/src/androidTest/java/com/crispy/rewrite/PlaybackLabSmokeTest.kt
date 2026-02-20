package com.crispy.rewrite

import android.Manifest
import android.content.Context
import android.view.SurfaceView
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.rule.GrantPermissionRule
import com.crispy.rewrite.nativeengine.playback.NativePlaybackEngine
import com.crispy.rewrite.nativeengine.playback.NativePlaybackEvent
import com.crispy.rewrite.nativeengine.playback.PlaybackController
import com.crispy.rewrite.player.CatalogLabResult
import com.crispy.rewrite.player.CatalogPageRequest
import com.crispy.rewrite.player.CatalogSearchLabService
import com.crispy.rewrite.player.CatalogSearchRequest
import com.crispy.rewrite.player.MetadataLabMediaType
import com.crispy.rewrite.player.MetadataLabRequest
import com.crispy.rewrite.player.MetadataLabResolution
import com.crispy.rewrite.player.MetadataLabResolver
import com.crispy.rewrite.player.SupabaseSyncAuthState
import com.crispy.rewrite.player.SupabaseSyncLabResult
import com.crispy.rewrite.player.SupabaseSyncLabService
import com.crispy.rewrite.player.WatchHistoryEntry
import com.crispy.rewrite.player.WatchHistoryLabResult
import com.crispy.rewrite.player.WatchHistoryLabService
import com.crispy.rewrite.player.WatchHistoryRequest
import com.crispy.rewrite.player.WatchProviderAuthState
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class PlaybackLabSmokeTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val notificationPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @Before
    fun setUp() {
        TestPlaybackController.reset()
        TestTorrentResolver.reset()
        TestMetadataResolver.reset()

        PlaybackLabDependencies.playbackControllerFactory = { _, callback ->
            TestPlaybackController(callback)
        }
        PlaybackLabDependencies.torrentResolverFactory = { _ ->
            TestTorrentResolver(
                streamUrl = "http://127.0.0.1:8090/play/mockhash/0"
            )
        }
        PlaybackLabDependencies.metadataResolverFactory = { _ ->
            TestMetadataResolver()
        }
        PlaybackLabDependencies.catalogSearchServiceFactory = { _ ->
            TestCatalogSearchService()
        }
        PlaybackLabDependencies.watchHistoryServiceFactory = { _ ->
            TestWatchHistoryService()
        }
        PlaybackLabDependencies.supabaseSyncServiceFactory = { _, _ ->
            TestSupabaseSyncService()
        }
    }

    @After
    fun tearDown() {
        PlaybackLabDependencies.reset()
        TestPlaybackController.reset()
        TestTorrentResolver.reset()
        TestMetadataResolver.reset()
    }

    @Test
    fun sampleAndMockedTorrentFlow_playbackRequestsIssued() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        try {
            composeRule.onNodeWithTag("play_sample_button").performClick()

            composeRule.waitUntil(timeoutMillis = 5_000) {
                TestPlaybackController.playCalls.any {
                    it.url.contains("Big_Buck_Bunny_1080_10s_30MB.mp4") &&
                        it.engine == NativePlaybackEngine.EXO
                }
            }

            composeRule.onNodeWithTag("magnet_input").performTextClearance()
            composeRule.onNodeWithTag("magnet_input")
                .performTextInput("magnet:?xt=urn:btih:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
            composeRule.onNodeWithTag("start_torrent_button").performClick()

            composeRule.waitUntil(timeoutMillis = 5_000) {
                TestPlaybackController.playCalls.any {
                    it.url == "http://127.0.0.1:8090/play/mockhash/0" &&
                        it.engine == NativePlaybackEngine.EXO
                }
            }

            composeRule.onNodeWithTag("status_text")
                .assertTextContains("Playback ready")

            composeRule.onNodeWithTag("metadata_id_input").performTextClearance()
            composeRule.onNodeWithTag("metadata_id_input").performTextInput("tmdb:1399:1:2")
            composeRule.onNodeWithTag("resolve_metadata_button").performClick()

            composeRule.waitUntil(timeoutMillis = 5_000) {
                TestMetadataResolver.requests.isNotEmpty()
            }

            composeRule.onNodeWithTag("metadata_primary_text")
                .assertTextContains("Game of Thrones")
            composeRule.onNodeWithTag("metadata_bridge_text")
                .assertTextContains("tt0944947:1:2")
            composeRule.onNodeWithTag("metadata_transport_text")
                .assertTextContains("transport=")
        } finally {
            scenario.close()
        }
    }
}

private data class PlayCall(
    val url: String,
    val engine: NativePlaybackEngine
)

private class TestPlaybackController(
    private val callback: (NativePlaybackEvent) -> Unit
) : PlaybackController {
    private var positionMs: Long = 0L

    override fun play(url: String, engine: NativePlaybackEngine) {
        playCalls += PlayCall(url = url, engine = engine)
        positionMs = 0L
        callback(NativePlaybackEvent.Ready)
    }

    override fun seekTo(positionMs: Long) {
        this.positionMs = positionMs
    }

    override fun currentPositionMs(): Long {
        return positionMs
    }

    override fun stop() {
        stopCallCount += 1
        positionMs = 0L
    }

    override fun release() {
        releaseCallCount += 1
    }

    override fun bindExoPlayerView(playerView: PlayerView) {
        // No-op for test fake.
    }

    override fun createVlcSurfaceView(context: Context): SurfaceView {
        return SurfaceView(context)
    }

    override fun attachVlcSurface(surfaceView: SurfaceView) {
        // No-op for test fake.
    }

    companion object {
        val playCalls: MutableList<PlayCall> = CopyOnWriteArrayList()
        var stopCallCount: Int = 0
        var releaseCallCount: Int = 0

        fun reset() {
            playCalls.clear()
            stopCallCount = 0
            releaseCallCount = 0
        }
    }
}

private class TestTorrentResolver(
    private val streamUrl: String
) : TorrentResolver {
    override suspend fun resolveStreamUrl(magnetLink: String, sessionId: String): String {
        requests += "$sessionId|$magnetLink"
        return streamUrl
    }

    override fun stopAndClear() {
        stopCallCount += 1
    }

    override fun close() {
        closeCallCount += 1
    }

    companion object {
        val requests: MutableList<String> = CopyOnWriteArrayList()
        var stopCallCount: Int = 0
        var closeCallCount: Int = 0

        fun reset() {
            requests.clear()
            stopCallCount = 0
            closeCallCount = 0
        }
    }
}

private class TestMetadataResolver : MetadataLabResolver {
    override suspend fun resolve(request: MetadataLabRequest): MetadataLabResolution {
        requests += "${request.mediaType}|${request.rawId}"

        val contentId = if (request.rawId.startsWith("tmdb:1399")) "tmdb:1399" else "tt1375666"
        val hasEpisode = request.rawId.endsWith(":1:2")
        val videoId = if (hasEpisode) "$contentId:1:2" else null
        val bridgeCandidates = if (hasEpisode && contentId == "tmdb:1399") {
            listOf("tmdb:1399:1:2", "tt0944947:1:2")
        } else {
            listOf(contentId)
        }

        return MetadataLabResolution(
            contentId = contentId,
            videoId = videoId,
            addonLookupId = videoId ?: contentId,
            primaryId = if (request.mediaType == MetadataLabMediaType.SERIES) "tt0944947" else "tt1375666",
            primaryTitle = if (request.mediaType == MetadataLabMediaType.SERIES) "Game of Thrones" else "Inception",
            sources = listOf("com.linvo.cinemeta", "meta.community"),
            needsEnrichment = contentId.startsWith("tmdb:"),
            bridgeCandidateIds = bridgeCandidates,
            mergedImdbId = if (contentId == "tmdb:1399") "tt0944947" else "tt1375666",
            mergedSeasonNumbers = if (request.mediaType == MetadataLabMediaType.SERIES) listOf(1, 2) else emptyList()
        )
    }

    companion object {
        val requests: MutableList<String> = CopyOnWriteArrayList()

        fun reset() {
            requests.clear()
        }
    }
}

private class TestCatalogSearchService : CatalogSearchLabService {
    override suspend fun fetchCatalogPage(request: CatalogPageRequest): CatalogLabResult {
        return CatalogLabResult(statusMessage = "Catalog test stub")
    }

    override suspend fun search(request: CatalogSearchRequest): CatalogLabResult {
        return CatalogLabResult(statusMessage = "Catalog test stub")
    }
}

private class TestWatchHistoryService : WatchHistoryLabService {
    override fun updateAuthTokens(traktAccessToken: String, simklAccessToken: String) {
        // No-op for test fake.
    }

    override fun authState(): WatchProviderAuthState {
        return WatchProviderAuthState()
    }

    override suspend fun listLocalHistory(limit: Int): WatchHistoryLabResult {
        return WatchHistoryLabResult(
            statusMessage = "Watch history test stub",
            entries = emptyList()
        )
    }

    override suspend fun markWatched(request: WatchHistoryRequest): WatchHistoryLabResult {
        return WatchHistoryLabResult(
            statusMessage = "Watch history test stub",
            entries = emptyList<WatchHistoryEntry>()
        )
    }

    override suspend fun exportLocalHistory(): List<WatchHistoryEntry> {
        return emptyList()
    }

    override suspend fun replaceLocalHistory(entries: List<WatchHistoryEntry>): WatchHistoryLabResult {
        return WatchHistoryLabResult(
            statusMessage = "Watch history test stub",
            entries = entries
        )
    }

    override suspend fun unmarkWatched(request: WatchHistoryRequest): WatchHistoryLabResult {
        return WatchHistoryLabResult(
            statusMessage = "Watch history test stub",
            entries = emptyList<WatchHistoryEntry>()
        )
    }
}

private class TestSupabaseSyncService : SupabaseSyncLabService {
    override suspend fun initialize(): SupabaseSyncLabResult {
        return SupabaseSyncLabResult(statusMessage = "Supabase test stub")
    }

    override suspend fun signUpWithEmail(email: String, password: String): SupabaseSyncLabResult {
        return SupabaseSyncLabResult(statusMessage = "Supabase test stub")
    }

    override suspend fun signInWithEmail(email: String, password: String): SupabaseSyncLabResult {
        return SupabaseSyncLabResult(statusMessage = "Supabase test stub")
    }

    override suspend fun signOut(): SupabaseSyncLabResult {
        return SupabaseSyncLabResult(statusMessage = "Supabase test stub")
    }

    override suspend fun pushAllLocalData(): SupabaseSyncLabResult {
        return SupabaseSyncLabResult(statusMessage = "Supabase test stub")
    }

    override suspend fun pullAllToLocal(): SupabaseSyncLabResult {
        return SupabaseSyncLabResult(statusMessage = "Supabase test stub")
    }

    override suspend fun syncNow(): SupabaseSyncLabResult {
        return SupabaseSyncLabResult(statusMessage = "Supabase test stub")
    }

    override suspend fun generateSyncCode(pin: String): SupabaseSyncLabResult {
        return SupabaseSyncLabResult(statusMessage = "Supabase test stub", syncCode = "ABC123")
    }

    override suspend fun claimSyncCode(code: String, pin: String): SupabaseSyncLabResult {
        return SupabaseSyncLabResult(statusMessage = "Supabase test stub")
    }

    override fun authState(): SupabaseSyncAuthState {
        return SupabaseSyncAuthState(configured = true)
    }
}

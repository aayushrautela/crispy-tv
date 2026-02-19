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

        PlaybackLabDependencies.playbackControllerFactory = { _, callback ->
            TestPlaybackController(callback)
        }
        PlaybackLabDependencies.torrentResolverFactory = { _ ->
            TestTorrentResolver(
                streamUrl = "http://127.0.0.1:8090/play/mockhash/0"
            )
        }
    }

    @After
    fun tearDown() {
        PlaybackLabDependencies.reset()
        TestPlaybackController.reset()
        TestTorrentResolver.reset()
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
    override fun play(url: String, engine: NativePlaybackEngine) {
        playCalls += PlayCall(url = url, engine = engine)
        callback(NativePlaybackEvent.Ready)
    }

    override fun stop() {
        stopCallCount += 1
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

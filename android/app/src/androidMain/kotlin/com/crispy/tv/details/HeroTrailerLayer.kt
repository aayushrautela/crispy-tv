package com.crispy.tv.details

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.crispy.tv.PlaybackDependencies
import com.crispy.tv.details.trailer.TrailerPlaybackSource
import com.crispy.tv.details.trailer.TrailerSource
import com.crispy.tv.distribution.AppDistribution
import com.crispy.tv.nativeengine.playback.NativePlaybackState
import com.crispy.tv.nativeengine.playback.NativeTrailerPlayer
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * The Android half of the details hero, cut out of `DetailsHero.kt`.
 *
 * **A video surface is not a value and not a composable shape**: it is a mpv
 * instance and the `SurfaceView` it renders into, so it has no portable form --
 * the same reason the surface plumbing stayed in `:android:native-engine` while
 * the session port moved to `:core-domain`.
 *
 * `HeroSection` reaches it through one `@Composable` slot rather than calling it,
 * which is why this file stays in `androidMain` while `DetailsHero.kt` does not.
 * Everything above it -- the hero image, the focus handling, the artwork crossfade,
 * the cover alpha -- is ordinary Compose.
 *
 * The trailer runs on its own mpv instance ([NativeTrailerPlayer]), not the
 * player's: the two are never on screen at once, but the trailer's mute and crop
 * would fight the player's own state if they shared one.
 *
 * Its own signature is reproduced at the slot **verbatim**, nine parameters and all,
 * because the alternative is a request object that only exists to be a lambda
 * payload, and inventing one here would be a shape decision wearing a port's
 * clothes. The one parameter list in this module that genuinely wants a value type
 * is `PlayerOverlay`'s 21 callbacks; that is recorded there, not paid for here.
 */
@Composable
internal fun HeroTrailerLayer(
    modifier: Modifier,
    trailer: List<HeroTrailerSource>,
    viewportWidthPx: Int,
    viewportHeightPx: Int,
    shouldPlay: Boolean,
    isMuted: Boolean,
    onFirstFrameRendered: () -> Unit,
    onPlaybackState: (state: Int, timeSeconds: Double) -> Unit,
    onFocusLossPause: () -> Unit,
) {
    val context = LocalContext.current
    val audioFocusManager = PlaybackDependencies.getAudioFocusManager(context)

    val latestOnFirstFrameRendered = rememberUpdatedState(onFirstFrameRendered)
    val latestOnPlaybackState = rememberUpdatedState(onPlaybackState)

    var currentIndex by remember(trailer) { mutableStateOf(0) }
    var source by remember(trailer) { mutableStateOf<TrailerPlaybackSource?>(null) }
    var advanceRequests by remember(trailer) { mutableStateOf(0) }

    LaunchedEffect(trailer) {
        currentIndex = 0
        source = null
        advanceRequests = 0
    }

    val currentEntry = trailer.getOrNull(currentIndex)

    LaunchedEffect(currentEntry, shouldPlay) {
        if (!shouldPlay) return@LaunchedEffect
        if (source != null) return@LaunchedEffect
        val entry = currentEntry ?: return@LaunchedEffect
        source = when (entry.source) {
            TrailerSource.DIRECT -> TrailerPlaybackSource(videoUrl = entry.id)
            TrailerSource.YOUTUBE -> withContext(Dispatchers.IO) {
                AppDistribution.current.trailerExtractor.resolve(
                    videoId = entry.id,
                    viewportWidthPx = viewportWidthPx,
                    viewportHeightPx = viewportHeightPx,
                )
            }
        }
        if (source == null) advanceRequests++
    }

    LaunchedEffect(advanceRequests) {
        if (advanceRequests <= 0) return@LaunchedEffect
        val next = currentIndex + 1
        if (next < trailer.size) {
            currentIndex = next
            source = null
        }
    }

    val playbackSource = source ?: return

    // One mpv instance per resolved source: a new URL means a fresh instance,
    // because mpv's loadfile state (crop, mute) is per instance and a retry of
    // the same URL is the only case that wants the previous instance kept.
    val trailerPlayer = remember(playbackSource.videoUrl, playbackSource.audioUrl) {
        NativeTrailerPlayer(context).apply {
            setCropped(true)
        }
    }

    DisposableEffect(trailerPlayer) {
        audioFocusManager.registerSource("trailer", pauseHandler = onFocusLossPause)
        onDispose {
            audioFocusManager.unregisterSource("trailer")
            audioFocusManager.release("trailer")
            trailerPlayer.release()
        }
    }

    var hasRenderedFirstFrame by remember(playbackSource.videoUrl, playbackSource.audioUrl) { mutableStateOf(false) }
    var lastSentState by remember(playbackSource.videoUrl, playbackSource.audioUrl) { mutableStateOf<Int?>(null) }
    var lastErrorToken by remember(playbackSource.videoUrl, playbackSource.audioUrl) { mutableStateOf<Long?>(null) }

    fun sendState(state: Int, positionMs: Long) {
        if (lastSentState == state) return
        lastSentState = state
        latestOnPlaybackState.value(state, positionMs / 1000.0)
        if (state == 0) audioFocusManager.release("trailer")
    }

    LaunchedEffect(trailerPlayer, playbackSource.videoUrl, playbackSource.audioUrl) {
        hasRenderedFirstFrame = false
        lastSentState = null
        lastErrorToken = null
        trailerPlayer.play(playbackSource.videoUrl, playbackSource.audioUrl)
    }

    // mpv reports state through observed properties rather than callbacks, so
    // the layer polls the snapshot the way the player screen does.
    LaunchedEffect(trailerPlayer) {
        while (coroutineContext.isActive) {
            val snapshot = trailerPlayer.snapshot()
            val error = snapshot.error
            if (error != null && lastErrorToken != error.token) {
                lastErrorToken = error.token
                advanceRequests++
            } else if (error == null) {
                if (snapshot.state == NativePlaybackState.PLAYING && !hasRenderedFirstFrame) {
                    hasRenderedFirstFrame = true
                    latestOnFirstFrameRendered.value()
                }
                val state =
                    when (snapshot.state) {
                        NativePlaybackState.PLAYING -> 1
                        NativePlaybackState.ENDED -> 0
                        NativePlaybackState.PREPARING, NativePlaybackState.BUFFERING -> 3
                        else -> 2
                    }
                sendState(state, snapshot.positionMs)
            }
            delay(250)
        }
    }

    SideEffect {
        trailerPlayer.setMuted(isMuted)
    }

    LaunchedEffect(trailerPlayer, shouldPlay) {
        trailerPlayer.setPlaying(shouldPlay)
        if (shouldPlay) {
            audioFocusManager.acquire("trailer")
        } else {
            audioFocusManager.release("trailer")
        }
    }

    // The trailer runs on `vo=gpu` with a panscan cover-crop, so mpv renders
    // the cropped frame into the surface and the view just fills the hero.
    AndroidView(
        modifier = modifier,
        factory = { viewContext -> trailerPlayer.createSurfaceView(viewContext) },
        update = { surfaceView -> trailerPlayer.attach(surfaceView) },
    )
}

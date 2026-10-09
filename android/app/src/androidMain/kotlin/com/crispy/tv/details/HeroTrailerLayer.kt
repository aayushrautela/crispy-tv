package com.crispy.tv.details

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import com.crispy.tv.details.trailer.TrailerSource

/**
 * The Android half of the details hero, cut out of `DetailsHero.kt`.
 *
 * **A video surface is not a value and not a composable shape**: it is an ExoPlayer
 * instance, an inflated `PlayerView`, a libass overlay and a SurfaceView, so it has
 * no portable form -- the same reason `PlaybackSurfaceController` stayed in
 * `:android:native-engine` while `PlaybackSessionController` moved.
 *
 * `HeroSection` reaches it through one `@Composable` slot rather than calling it,
 * which is why this file stays in `androidMain` while `DetailsHero.kt` does not.
 * Everything above it -- the hero image, the focus handling, the artwork crossfade,
 * the cover alpha -- is ordinary Compose.
 *
 * Its own signature is reproduced at the slot **verbatim**, nine parameters and all,
 * because the alternative is a request object that only exists to be a lambda
 * payload, and inventing one here would be a shape decision wearing a port's
 * clothes. The one parameter list in this module that genuinely wants a value type
 * is `PlayerOverlay`'s 21 callbacks; that is recorded there, not paid for here.
 */
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.crispy.tv.PlaybackDependencies
import com.crispy.tv.details.trailer.TrailerPlaybackSource
import com.crispy.tv.distribution.AppDistribution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
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
    val latestShouldPlay = rememberUpdatedState(shouldPlay)

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

    val exoPlayer = remember(playbackSource.videoUrl, playbackSource.audioUrl) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context))
            .build()
            .apply {
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                volume = if (isMuted) 0f else 1f
            }
    }

    DisposableEffect(exoPlayer) {
        audioFocusManager.registerSource("trailer", pauseHandler = onFocusLossPause)
        onDispose {
            audioFocusManager.unregisterSource("trailer")
            audioFocusManager.release("trailer")
            runCatching { exoPlayer.release() }
        }
    }

    var hasRenderedFirstFrame by remember(playbackSource.videoUrl, playbackSource.audioUrl) { mutableStateOf(false) }
    var lastSentState by remember(playbackSource.videoUrl, playbackSource.audioUrl) { mutableStateOf<Int?>(null) }

    fun sendState(state: Int) {
        if (lastSentState == state) return
        lastSentState = state
        latestOnPlaybackState.value(state, exoPlayer.currentPosition / 1000.0)
        if (state == 0) audioFocusManager.release("trailer")
    }

    DisposableEffect(exoPlayer) {
        var errored = false
        val listener =
            object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    hasRenderedFirstFrame = true
                    latestOnFirstFrameRendered.value()
                    if (latestShouldPlay.value) {
                        sendState(1)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (!errored) {
                        errored = true
                        advanceRequests++
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        if (hasRenderedFirstFrame) {
                            sendState(1)
                        }
                        return
                    }

                    when (exoPlayer.playbackState) {
                        Player.STATE_ENDED -> sendState(0)
                        Player.STATE_BUFFERING -> sendState(3)
                        else -> sendState(2)
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (exoPlayer.isPlaying) return
                    when (playbackState) {
                        Player.STATE_ENDED -> sendState(0)
                        Player.STATE_BUFFERING -> sendState(3)
                    }
                }
            }

        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    LaunchedEffect(exoPlayer, playbackSource.videoUrl, playbackSource.audioUrl) {
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
        val videoSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(playbackSource.videoUrl))
        val mediaSource =
            playbackSource.audioUrl?.let { audioUrl ->
                MergingMediaSource(
                    videoSource,
                    mediaSourceFactory.createMediaSource(MediaItem.fromUri(audioUrl))
                )
            } ?: videoSource

        hasRenderedFirstFrame = false
        lastSentState = null

        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
    }

    SideEffect {
        exoPlayer.volume = if (isMuted) 0f else 1f
    }

    LaunchedEffect(exoPlayer, shouldPlay) {
        exoPlayer.playWhenReady = shouldPlay
        if (shouldPlay) {
            exoPlayer.play()
            audioFocusManager.acquire("trailer")
        } else {
            exoPlayer.pause()
            audioFocusManager.release("trailer")
        }
    }

    Box(
        modifier = modifier.clipToBounds()
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(com.crispy.tv.app.R.layout.hero_trailer_player_view, null, false) as PlayerView).apply {
                    layoutParams =
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    useController = false
                    controllerAutoShow = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    isEnabled = false
                    isClickable = false
                    isLongClickable = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    player = exoPlayer
                    videoSurfaceView?.apply {
                        isClickable = false
                        isLongClickable = false
                        isFocusable = false
                        isFocusableInTouchMode = false
                    }
                }
            },
            update = { view ->
                view.player = exoPlayer
            }
        )
    }
}

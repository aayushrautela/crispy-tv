package com.crispy.tv.tv.ui.screens.detail

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
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
import com.crispy.tv.details.trailer.TrailerPlaybackSource
import com.crispy.tv.details.trailer.YouTubeTrailerExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
@Composable
internal fun TvHeroTrailerLayer(
    trailers: List<TvTrailerEntry>,
    shouldPlay: Boolean,
    isMuted: Boolean,
    onAllSourcesFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    var currentIndex by remember(trailers) { mutableIntStateOf(0) }
    var source by remember(trailers) { mutableStateOf<TrailerPlaybackSource?>(null) }
    var advanceRequests by remember(trailers) { mutableIntStateOf(0) }

    LaunchedEffect(trailers) {
        currentIndex = 0
        source = null
        advanceRequests = 0
    }

    val currentEntry = trailers.getOrNull(currentIndex)

    LaunchedEffect(currentEntry, shouldPlay) {
        if (!shouldPlay) return@LaunchedEffect
        if (source != null) return@LaunchedEffect
        val entry = currentEntry ?: return@LaunchedEffect
        val metrics = context.resources.displayMetrics
        source = when (entry.source) {
            TrailerSource.DIRECT -> TrailerPlaybackSource(videoUrl = entry.id)
            TrailerSource.YOUTUBE -> withContext(Dispatchers.IO) {
                YouTubeTrailerExtractor.resolve(
                    videoId = entry.id,
                    viewportWidthPx = metrics.widthPixels,
                    viewportHeightPx = metrics.heightPixels,
                )
            }
        }
        if (source == null) advanceRequests++
    }

    LaunchedEffect(advanceRequests) {
        if (advanceRequests <= 0) return@LaunchedEffect
        val next = currentIndex + 1
        if (next < trailers.size) {
            currentIndex = next
            source = null
        } else {
            onAllSourcesFailed()
        }
    }

    val playbackSource = source ?: return

    val exoPlayer = remember(playbackSource.videoUrl, playbackSource.audioUrl) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context))
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                volume = if (isMuted) 0f else 1f
            }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            runCatching { exoPlayer.release() }
        }
    }

    DisposableEffect(exoPlayer) {
        var errored = false
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (!errored) {
                    errored = true
                    advanceRequests++
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
                    mediaSourceFactory.createMediaSource(MediaItem.fromUri(audioUrl)),
                )
            } ?: videoSource

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
        } else {
            exoPlayer.pause()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { view ->
            view.player = exoPlayer
        },
        modifier = modifier.fillMaxSize(),
    )
}

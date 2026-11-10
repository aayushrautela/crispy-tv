package com.crispy.tv.tv.ui.screens.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.crispy.tv.details.trailer.TrailerPlaybackSource
import com.crispy.tv.details.trailer.TrailerSource
import com.crispy.tv.nativeengine.playback.NativePlaybackState
import com.crispy.tv.nativeengine.playback.NativeTrailerPlayer
import com.crispy.tv.youtubetractor.YouTubeTrailerExtractor
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
internal fun TvHeroTrailerLayer(
    trailers: List<TvTrailerEntry>,
    shouldPlay: Boolean,
    isMuted: Boolean,
    onAllSourcesFailed: () -> Unit,
    onEnded: () -> Unit,
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

    // One mpv instance per resolved source: a new URL means a fresh instance,
    // because mpv's loadfile state (crop, mute) is per instance.
    val trailerPlayer = remember(playbackSource.videoUrl, playbackSource.audioUrl) {
        NativeTrailerPlayer(context).apply {
            setCropped(true)
        }
    }

    DisposableEffect(trailerPlayer) {
        onDispose {
            trailerPlayer.release()
        }
    }

    var lastErrorToken by remember(playbackSource.videoUrl, playbackSource.audioUrl) { mutableStateOf<Long?>(null) }
    var endedReported by remember(playbackSource.videoUrl, playbackSource.audioUrl) { mutableStateOf(false) }
    var videoAspect by remember(playbackSource.videoUrl, playbackSource.audioUrl) { mutableStateOf<Float?>(null) }

    LaunchedEffect(trailerPlayer, playbackSource.videoUrl, playbackSource.audioUrl) {
        lastErrorToken = null
        endedReported = false
        videoAspect = null
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
                snapshot.videoLayout?.aspectRatioValue()?.let { videoAspect = it }
                if (snapshot.state == NativePlaybackState.ENDED && !endedReported) {
                    endedReported = true
                    onEnded()
                }
            }
            delay(250)
        }
    }

    SideEffect {
        trailerPlayer.setMuted(isMuted)
    }

    LaunchedEffect(trailerPlayer, shouldPlay) {
        trailerPlayer.setPlaying(shouldPlay)
    }

    Box(modifier = modifier) {
        // Size the surface to the video's own aspect, the way the phone player
        // route does: the tunneled vo renders at the video's native size and the
        // view scales it, so a viewport-sized surface would stretch the picture.
        val metrics = context.resources.displayMetrics
        val viewportAspect = metrics.widthPixels.toFloat() / metrics.heightPixels.toFloat()
        val aspect = videoAspect
        val surfaceSizeModifier =
            if (aspect != null && aspect > 0f && viewportAspect > 0f) {
                val fitted = Modifier.align(Alignment.Center).aspectRatio(aspect)
                if (aspect >= viewportAspect) fitted.fillMaxWidth() else fitted.fillMaxHeight()
            } else {
                Modifier.fillMaxSize()
            }
        AndroidView(
            factory = { ctx -> trailerPlayer.createSurfaceView(ctx) },
            update = { surfaceView -> trailerPlayer.attach(surfaceView) },
            modifier = surfaceSizeModifier,
        )
    }
}

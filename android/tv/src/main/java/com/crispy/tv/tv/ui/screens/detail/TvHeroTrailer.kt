package com.crispy.tv.tv.ui.screens.detail

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

    LaunchedEffect(trailerPlayer, playbackSource.videoUrl, playbackSource.audioUrl) {
        lastErrorToken = null
        endedReported = false
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

    // The trailer runs on `vo=gpu` with a panscan cover-crop, so mpv renders
    // the cropped frame into the surface and the view just fills the hero.
    AndroidView(
        factory = { ctx -> trailerPlayer.createSurfaceView(ctx) },
        update = { surfaceView -> trailerPlayer.attach(surfaceView) },
        modifier = modifier,
    )
}

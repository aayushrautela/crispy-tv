package com.crispy.tv.tv.player

import android.app.Application
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.crispy.tv.addons.lookup.parseAddonEpisodeLookupId
import com.crispy.tv.addons.lookup.toMetadataLabMediaTypeOrNull
import com.crispy.tv.addons.playback.resolvePlaybackSource
import com.crispy.tv.addons.streams.AddonStream
import com.crispy.tv.nativeengine.playback.NativePlaybackController
import com.crispy.tv.nativeengine.playback.NativePlaybackEngine
import com.crispy.tv.nativeengine.playback.NativePlaybackSnapshot
import com.crispy.tv.nativeengine.playback.NativePlaybackState
import com.crispy.tv.nativeengine.playback.NativeTrack
import com.crispy.tv.nativeengine.playback.PlaybackSource
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.tv.di.TvServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * What the TV player shows, and what it can be asked to do.
 *
 * ## Why the sheet is part of the state
 *
 * A TV has no pointer, so every choice the viewer makes is a **D-pad choice**: the
 * sheet is not a floating dialog that the pointer dismisses, it is a list the viewer
 * walks with the up and down keys. That makes the open sheet part of what the screen
 * is showing, and not a piece of local UI state -- the view model has to know about
 * it, because the keys it handles depend on it.
 */
enum class TvPlayerOverlay {
    NONE,
    OPTIONS,
    SUBTITLES,
    AUDIO,
    SPEED,
}

/**
 * The player's state, as the screen renders it.
 *
 * The state is a **reading of the engine's snapshot**, never a second copy of it:
 * position, duration, tracks and the play state all come from
 * [NativePlaybackSnapshot], and this type adds only the things the engine does not
 * know -- which title is playing, which overlay is open, and what the viewer was
 * told.
 */
data class TvPlayerUiState(
    val preparing: Boolean = true,
    val title: String? = null,
    val episodeLabel: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val errorMessage: String? = null,
    val audioTracks: List<NativeTrack> = emptyList(),
    val selectedAudioTrackId: String? = null,
    val subtitleTracks: List<NativeTrack> = emptyList(),
    val selectedSubtitleTrackId: String? = null,
    val playbackSpeed: Float = 1f,
    val muted: Boolean = false,
    val overlay: TvPlayerOverlay = TvPlayerOverlay.NONE,
    val notice: String? = null,
    /** The lookup the picked source came from, so "change source" can go back to it. */
    val lookupMediaTypeName: String? = null,
    val lookupId: String? = null,
    /** The episode being played, when it is one. */
    val season: Int? = null,
    val episode: Int? = null,
) {
    val hasError: Boolean get() = !errorMessage.isNullOrBlank()

    val progressFraction: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

    /** The tracks a viewer can choose between, or an empty list when there are none. */
    val hasSubtitles: Boolean get() = subtitleTracks.isNotEmpty()
    val hasAudioTracks: Boolean get() = audioTracks.isNotEmpty()
}

/**
 * The TV's playback session.
 *
 * ## What this replaces
 *
 * The TV used to build a raw `ExoPlayer` in this view model and play a URL that
 * had been smuggled through the navigation route as a query argument. **That lost
 * two things a provider can require**: the request headers, without which a stream
 * that plays on the phone does not play here, and the sidecar subtitles, without
 * which a foreign-language stream has no subtitles at all. Both now travel with the
 * pick itself, and the engine behind them is the project's own
 * [NativePlaybackController] -- the same engine the phone plays through.
 *
 * ## What it writes down
 *
 * Progress goes to the **shared watch-history service**, not to the backend
 * directly, because that service already knows how to write the local store the
 * phone reads *and* how to send the backend event. The TV used to send its own
 * events under its own key; writing through the service is what makes the two apps
 * agree on where a viewer left off.
 */
@OptIn(UnstableApi::class)
class TvPlayerViewModel(
    app: Application,
    private val itemId: String,
    private val selection: TvPlaybackSelection?,
) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(TvPlayerUiState())
    val state: StateFlow<TvPlayerUiState> = _state.asStateFlow()

    /**
     * The scope of the **last** progress write.
     *
     * The view model's own scope is cancelled the moment the view model is cleared,
     * which is the moment the viewer leaves the player -- so a write started there
     * would be cancelled half way through and the viewer's last position would be
     * lost. This scope outlives the view model by exactly as long as the write
     * takes, and is not cancelled: it has one child and no future.
     */
    private val finalReportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var controller: NativePlaybackController? = null
    private var pendingPlayerView: PlayerView? = null
    private var pollJob: Job? = null
    private var identity: PlaybackIdentity? = null
    private var startedReported = false
    private var lastReportedPositionMs = -1L
    private var completedReported = false

    init {
        prepare()
    }

    /**
     * The video surface.
     *
     * The surface is attached to the engine rather than owned by the screen, because
     * the engine owns the player the surface shows. A surface that arrives before
     * the engine does is kept and attached when the engine is built.
     */
    fun bindPlayerView(playerView: PlayerView) {
        val engine = controller
        if (engine == null) {
            pendingPlayerView = playerView
            return
        }
        engine.bindExoPlayerView(playerView)
    }

    fun togglePlayPause() {
        val engine = controller ?: return
        engine.setPlaying(!_state.value.isPlaying)
    }

    /** The D-pad's left and right: ten seconds back, ten seconds on. */
    fun seekBy(deltaMs: Long) {
        val engine = controller ?: return
        val target = (_state.value.positionMs + deltaMs).coerceAtLeast(0L)
        engine.seekTo(target)
    }

    fun seekTo(positionMs: Long) {
        val engine = controller ?: return
        engine.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun selectAudioTrack(trackId: String?) {
        val engine = controller ?: return
        engine.selectAudioTrack(trackId)
    }

    fun selectSubtitleTrack(trackId: String?) {
        val engine = controller ?: return
        engine.selectSubtitleTrack(trackId)
    }

    fun setPlaybackSpeed(speed: Float) {
        val engine = controller ?: return
        engine.setPlaybackSpeed(speed)
        _state.value = _state.value.copy(playbackSpeed = speed)
    }

    fun toggleMuted() {
        val engine = controller ?: return
        val next = !_state.value.muted
        engine.setMuted(next)
        _state.value = _state.value.copy(muted = next)
    }

    fun openOverlay(overlay: TvPlayerOverlay) {
        _state.value = _state.value.copy(overlay = overlay)
    }

    fun closeOverlay() {
        _state.value = _state.value.copy(overlay = TvPlayerOverlay.NONE)
    }

    /** The engine failed: show what it said, and let the viewer try again. */
    fun retry() {
        val stream = selection?.stream ?: return
        val engine = controller ?: return
        _state.value = _state.value.copy(errorMessage = null, preparing = true)
        startedReported = false
        completedReported = false
        lastReportedPositionMs = -1L
        viewModelScope.launch {
            val source = resolveSource(stream) ?: return@launch
            _state.value = _state.value.copy(preparing = false)
            engine.play(source, NativePlaybackEngine.EXO)
        }
    }

    fun onHostPaused() {
        val engine = controller ?: return
        engine.setPlaying(false)
        reportNow(force = true)
    }

    fun onHostResumed() {
        val engine = controller ?: return
        engine.setPlaying(true)
    }

    /**
     * The last word on where the viewer got to.
     *
     * Written on the scope that outlives the view model, because this is the one
     * write that must not be cancelled by the very act of leaving the player.
     */
    fun reportFinalPosition() {
        val engine = controller ?: return
        val identity = identity ?: return
        val snapshot = engine.snapshot()
        finalReportScope.launch {
            try {
                TvServices.watchHistoryService(getApplication<Application>()).onPlaybackProgress(
                    identity = identity,
                    positionMs = snapshot.positionMs,
                    durationMs = snapshot.durationMs,
                    isPlaying = false,
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
            }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        pollJob = null
        reportFinalPosition()
        val engine = controller
        controller = null
        pendingPlayerView = null
        engine?.release()
        super.onCleared()
    }

    /**
     * Get the stream playing.
     *
     * Three things have to be true before the first frame: there has to be a source,
     * the engine has to have been built, and the title has to have been identified
     * -- because the identity is what the watch progress is filed under, and a
     * progress entry filed under the wrong key is one the phone will never read.
     */
    private fun prepare() {
        val stream = selection?.stream
        if (stream == null) {
            _state.value = _state.value.copy(
                preparing = false,
                errorMessage = "Pick a source to play this title.",
            )
            return
        }
        viewModelScope.launch {
            val metadata = loadTitleMetadata()
            // The episode is known two ways and read both: out of the lookup id when
            // it carries one, and from the pick's own season and episode when it
            // does not -- the calendar rows resolve their episode from the series'
            // episode list, and the numbers are what the progress key needs.
            val parsedEpisode = parseAddonEpisodeLookupId(selection.lookupId)
            val seasonNumber = selection.season ?: parsedEpisode?.season
            val episodeNumber = selection.episode ?: parsedEpisode?.episode
            val isEpisode = seasonNumber != null && episodeNumber != null
            val contentType = contentTypeFor(selection.lookupMediaTypeName, isEpisode)
            identity = PlaybackIdentity(
                itemId = itemId,
                seriesItemId = if (contentType == MetadataLabMediaType.MOVIE) null else itemId,
                imdbId = parsedEpisode?.imdbId,
                contentType = contentType,
                season = seasonNumber,
                episode = episodeNumber,
                title = metadata?.title ?: itemId,
                year = metadata?.year,
                showTitle = if (contentType == MetadataLabMediaType.MOVIE) null else metadata?.title,
            )
            _state.value = _state.value.copy(
                title = metadata?.title,
                episodeLabel = if (isEpisode) "S$seasonNumber E$episodeNumber" else null,
                lookupMediaTypeName = selection.lookupMediaTypeName,
                lookupId = selection.lookupId,
                season = seasonNumber,
                episode = episodeNumber,
            )
            val engine = ensureController()
            // The stream is resolved here rather than in the picker, because a magnet
            // link is not playable until the torrent engine has turned it into a URL.
            // The player is where "preparing" belongs, and where the failure belongs.
            val source = resolveSource(stream) ?: return@launch
            _state.value = _state.value.copy(preparing = false)
            engine.play(source, NativePlaybackEngine.EXO)
            resumeIfNeeded(engine)
            startPolling()
        }
    }

    /**
     * The provider's stream, turned into something the engine can play.
     *
     * The rule is the shared one in `:android:addons`, because the phone answers the
     * same question and the two must not disagree: a plain URL is played directly,
     * with its headers and its subtitles, and a magnet link goes through the torrent
     * engine first.
     */
    private suspend fun resolveSource(stream: AddonStream): PlaybackSource? {
        val source = try {
            stream.resolvePlaybackSource(
                torrentResolver = TvServices.torrentResolver(getApplication<Application>()),
                sessionId = selection?.lookupId,
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _state.value = _state.value.copy(
                preparing = false,
                errorMessage = t.message ?: "The torrent engine could not start.",
            )
            return null
        }
        if (source == null) {
            _state.value = _state.value.copy(
                preparing = false,
                errorMessage = "This source could not be played.",
            )
        }
        return source
    }

    /**
     * The engine for this session.
     *
     * One engine per session, created when the session starts: the engine owns an
     * `ExoPlayer` and `release()` on it ends the session for whoever holds it.
     */
    private fun ensureController(): NativePlaybackController {
        val existing = controller
        if (existing != null) return existing
        val created = TvServices.newPlaybackController(getApplication<Application>())
        controller = created
        pendingPlayerView?.let { view ->
            created.bindExoPlayerView(view)
            pendingPlayerView = null
        }
        return created
    }

    /**
     * Where the viewer got to last time.
     *
     * **The resume point is the shared one**: the progress is read through the same
     * watch-history service, under the same key the phone writes, so a viewer who
     * started a title on the phone and finished it on the TV finds it finished.
     *
     * A position in the first few seconds is not a resume -- it is the viewer
     * changing their mind -- and a position in the last few seconds is a title that
     * has been watched, not one to resume.
     */
    private suspend fun resumeIfNeeded(engine: NativePlaybackController) {
        val identity = identity ?: return
        val progress = try {
            TvServices.watchHistoryService(getApplication<Application>()).getLocalWatchProgress(identity)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            null
        } ?: return
        val positionMs = (progress.currentTimeSeconds * 1000.0).toLong()
        val durationMs = (progress.durationSeconds * 1000.0).toLong()
        if (positionMs < ResumeThresholdMs) return
        if (durationMs > 0L && positionMs >= durationMs - ResumeTailMs) return
        engine.seekTo(positionMs)
        _state.value = _state.value.copy(
            notice = "Resumed at ${formatPlaybackTime(positionMs)}",
        )
    }

    /**
     * Follow the engine.
     *
     * The engine reports its state through a snapshot rather than through events,
     * so the screen is kept in step by asking. The poll is fast enough to move the
     * clock and slow enough not to be a battery story: half a second.
     */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                val engine = controller ?: break
                val snapshot = try {
                    engine.snapshot()
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    break
                }
                applySnapshot(snapshot)
                reportProgress(snapshot)
                delay(PollIntervalMs)
            }
        }
    }

    private fun applySnapshot(snapshot: NativePlaybackSnapshot) {
        val previous = _state.value
        val error = snapshot.error
        _state.value = previous.copy(
            preparing = if (snapshot.state == NativePlaybackState.IDLE) previous.preparing else false,
            isPlaying = snapshot.isPlaying,
            isBuffering = snapshot.isBuffering,
            positionMs = snapshot.positionMs,
            durationMs = snapshot.durationMs,
            playbackSpeed = snapshot.playbackSpeed,
            muted = snapshot.muted,
            audioTracks = snapshot.audioTracks,
            selectedAudioTrackId = snapshot.selectedAudioTrackId,
            subtitleTracks = snapshot.subtitleTracks,
            selectedSubtitleTrackId = snapshot.selectedSubtitleTrackId,
            errorMessage = error?.message ?: if (snapshot.state == NativePlaybackState.PLAYING) null else previous.errorMessage,
        )
        if (snapshot.state == NativePlaybackState.PLAYING && !startedReported) {
            startedReported = true
            viewModelScope.launch {
                reportStarted(snapshot)
            }
        }
        if (snapshot.state == NativePlaybackState.ENDED && !completedReported) {
            completedReported = true
            viewModelScope.launch {
                reportProgress(snapshot, force = true)
            }
        }
    }

    /**
     * Say that playback started.
     *
     * `onPlaybackStarted` is the phone's own way of saying it, and it writes the
     * same progress entry the phone writes -- so the TV does not need a second way
     * of saying the same thing.
     */
    private suspend fun reportStarted(snapshot: NativePlaybackSnapshot) {
        val identity = identity ?: return
        try {
            TvServices.watchHistoryService(getApplication<Application>())
                .onPlaybackStarted(identity, snapshot.positionMs, snapshot.durationMs)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
        }
    }

    /**
     * Write the position down.
     *
     * The write is throttled: a progress event every poll would be a hundred
     * network calls a minute for no extra truth. Once every ten seconds of playback,
     * and whenever the session pauses or ends, is enough to keep the resume point
     * honest.
     */
    private suspend fun reportProgress(snapshot: NativePlaybackSnapshot, force: Boolean = false) {
        val identity = identity ?: return
        val positionMs = snapshot.positionMs
        if (!force &&
            lastReportedPositionMs >= 0L &&
            positionMs - lastReportedPositionMs < ProgressReportIntervalMs
        ) {
            return
        }
        lastReportedPositionMs = positionMs
        try {
            TvServices.watchHistoryService(getApplication<Application>()).onPlaybackProgress(
                identity = identity,
                positionMs = positionMs,
                durationMs = snapshot.durationMs,
                isPlaying = snapshot.isPlaying,
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
        }
    }

    /**
     * Write the position down now, from the engine's current snapshot.
     */
    private fun reportNow(force: Boolean) {
        val engine = controller ?: return
        val snapshot = engine.snapshot()
        viewModelScope.launch {
            reportProgress(snapshot, force = force)
        }
    }

    /**
     * The title behind the stream.
     *
     * Best effort: a title that cannot be named is still playable, so a failure
     * here is a missing label rather than a broken player.
     */
    private suspend fun loadTitleMetadata(): TvTitleMetadata? {
        return try {
            val appContext = getApplication<Application>()
            val context = TvServices.contextResolver(appContext).resolve() ?: return null
            val item = TvServices.backendClient(appContext)
                .getMetadataItemDetail(context.accessToken, itemId)
                .item
            TvTitleMetadata(title = item.title, year = item.year)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            null
        }
    }

    /**
     * What kind of title this is, for the watch-progress key.
     *
     * An episode lookup means a series, whatever the caller said: the lookup id
     * carries a season and an episode, and a progress entry filed as a movie would
     * be filed under a key the phone never reads.
     */
    private fun contentTypeFor(mediaTypeName: String?, isEpisode: Boolean): MetadataLabMediaType {
        val declared = mediaTypeName?.toMetadataLabMediaTypeOrNull()
        if (isEpisode) {
            return if (declared == MetadataLabMediaType.ANIME) MetadataLabMediaType.ANIME else MetadataLabMediaType.SERIES
        }
        return declared ?: MetadataLabMediaType.MOVIE
    }
}

/**
 * The bits of a title the player shows.
 */
data class TvTitleMetadata(
    val title: String,
    val year: Int? = null,
)

/**
 * A position as a viewer reads it: `1:02:03` or `12:34`.
 */
fun formatPlaybackTime(positionMs: Long): String {
    val totalSeconds = (positionMs / 1000L).coerceAtLeast(0L)
    val seconds = totalSeconds % 60L
    val minutes = (totalSeconds / 60L) % 60L
    val hours = totalSeconds / 3600L
    return if (hours > 0L) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

private const val PollIntervalMs = 500L
private const val ProgressReportIntervalMs = 10_000L
private const val ResumeThresholdMs = 30_000L
private const val ResumeTailMs = 30_000L

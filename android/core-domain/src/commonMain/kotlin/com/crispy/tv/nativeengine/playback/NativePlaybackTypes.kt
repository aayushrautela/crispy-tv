package com.crispy.tv.nativeengine.playback

/**
 * The value types the playback engine reports and is asked to play.
 *
 * All of these were declared in `:android:native-engine`, which is a plain
 * `com.android.library` and therefore publishes no JVM variant -- so a KMP
 * `commonMain` could not name any of them, whatever the code itself touched.
 * Every one of them is plain data, and the 138-line file they lived in
 * contained exactly four platform imports in total, so the wall was the file's
 * *module* rather than any of these declarations.
 *
 * The package deliberately did not change, so the seven `:app` files that use
 * these types needed no import edit.
 *
 * The three declarations that were in the same file and did **not** come here:
 * `PlaybackSource.toOkHttpHeaders()` (its return type is okhttp's `Headers`),
 * `PlaybackSurfaceController` (all four of its members take a `PlayerView`, a
 * `SurfaceView` or a `Context`), and `PlaybackController` (the Android
 * composition of the two interfaces). Those stay in `:native-engine` because
 * they are the platform half, and a controller whose surface is a
 * `SurfaceView` is Android by definition.
 */
enum class NativePlaybackEngine {
    EXO,
    MPV,
}

/**
 * The coarse playback state the UI reacts to.
 *
 * [PREPARING] and [BUFFERING] are both "not playing, but not stopped", and
 * [NativePlaybackSnapshot.isBuffering] is what collapses them into the single
 * question the overlay actually asks.
 */
enum class NativePlaybackState {
    IDLE,
    PREPARING,
    BUFFERING,
    PLAYING,
    PAUSED,
    ENDED,
    ERROR,
}

/**
 * A playback failure, in the form the player screen can render it.
 *
 * [token] is the engine's own identifier for the failure and is what makes two
 * errors comparable without comparing [message], which is display text and is
 * not stable across engine versions. [codecLikely] is the engine's guess about
 * whether an unsupported codec is to blame, and it is what decides whether
 * [PlayerSessionDecisions] offers a codec fallback.
 */
data class NativePlaybackError(
    val token: Long,
    val message: String,
    val codecLikely: Boolean,
)

/**
 * The video surface's size, as the engine measured it.
 *
 * [width] and [height] are the encoded frame size, while [visibleWidth] and
 * [visibleHeight] are what is actually on screen after any cropping the
 * container applied, so they can disagree -- which is why [aspectRatioValue]
 * prefers the visible pair and falls back to the encoded one rather than
 * treating either as authoritative.
 */
data class NativeVideoLayout(
    val width: Int,
    val height: Int,
    val visibleWidth: Int,
    val visibleHeight: Int,
    val pixelWidthHeightRatio: Float = 1f,
) {
    /**
     * The aspect ratio to letterbox to, or `null` when no dimension is usable.
     *
     * Three separate fallbacks, each because the engine reports 0 or a
     * non-positive value while the surface is still being measured: the visible
     * pair over the encoded pair, `1f` over a non-positive
     * [pixelWidthHeightRatio], and `null` when neither dimension survives. A
     * ratio computed from zeros is `NaN`, and `NaN` reaches the layout as a
     * silently broken width rather than as a visible failure.
     */
    fun aspectRatioValue(): Float? {
        val effectiveWidth = visibleWidth.takeIf { it > 0 } ?: width
        val effectiveHeight = visibleHeight.takeIf { it > 0 } ?: height
        if (effectiveWidth <= 0 || effectiveHeight <= 0) {
            return null
        }

        val sanitizedPixelRatio = pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f
        return (effectiveWidth.toFloat() * sanitizedPixelRatio) / effectiveHeight.toFloat()
    }
}

/**
 * Everything the UI needs to know about the engine at one instant.
 *
 * Every field after [engine] has a default, because the engines build this
 * incrementally as playback progresses and the two of them do not populate the
 * same members at the same times -- so a snapshot taken during preparation
 * legitimately has no duration, no tracks and no layout.
 *
 * [isPlaying] and [isBuffering] are properties rather than stored flags
 * because they are *decisions* derived from [state], and a stored copy is a
 * third thing to keep in step. In particular [isBuffering] is one of two enum
 * values, so it cannot be a field without a `when` at every construction site.
 */
data class NativePlaybackSnapshot(
    val engine: NativePlaybackEngine,
    val state: NativePlaybackState = NativePlaybackState.IDLE,
    val playWhenReady: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferingPercent: Float? = null,
    val videoLayout: NativeVideoLayout? = null,
    val error: NativePlaybackError? = null,
    val playbackSpeed: Float = 1f,
    val muted: Boolean = false,
    val audioTracks: List<NativeTrack> = emptyList(),
    val selectedAudioTrackId: String? = null,
    val subtitleTracks: List<NativeTrack> = emptyList(),
    val selectedSubtitleTrackId: String? = null,
    val subtitleDelayMs: Int = 0,
) {
    val isPlaying: Boolean
        get() = state == NativePlaybackState.PLAYING

    val isBuffering: Boolean
        get() = state == NativePlaybackState.PREPARING || state == NativePlaybackState.BUFFERING
}

/**
 * One playable stream, with the headers needed to fetch it.
 *
 * [headers] is a `Map` and not a platform header type on purpose: the map is
 * what the shared code can carry, and `PlaybackSource.toOkHttpHeaders()` in
 * `:android:native-engine` is the one place that turns it into okhttp's
 * `Headers` on Android.
 */
data class PlaybackSource(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val streamType: String? = null,
    val externalSubtitles: List<PlaybackExternalSubtitle> = emptyList(),
)

/**
 * A subtitle file played alongside the video rather than muxed into it.
 *
 * Kept separate from [NativeTrack] because these have no selectable id: they
 * are side-loaded, so the UI lists them by [language] or [name] and cannot
 * offer a track picker for them.
 */
data class PlaybackExternalSubtitle(
    val url: String,
    val language: String? = null,
    val name: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

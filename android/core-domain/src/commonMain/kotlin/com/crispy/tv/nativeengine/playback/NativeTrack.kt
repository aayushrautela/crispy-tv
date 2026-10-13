package com.crispy.tv.nativeengine.playback

/**
 * A single audio or subtitle track as the engine reports it.
 *
 * Moved out of `:android:native-engine` for the same reason as [PlayerResizeMode] and
 * [NativePlaybackEnginePreference]: this type is part of the public shape of
 * `NativePlaybackSnapshot`, and a plain `com.android.library` cannot be referenced from
 * a KMP `commonMain` at all, so its old location held `PlayerTrackSheet.kt` -- 449
 * lines that name nothing platform-shaped -- in `androidMain` for want of a
 * five-field data class.
 *
 * The package deliberately did not change, so the three files in `:native-engine` and
 * the two in `:app` that use this type needed no import edit, and
 * `NativePlaybackSnapshot` still names it from the same package.
 *
 * [codec] and [channelCount] default to `null` because the two engines report them
 * to different depths -- mpv knows the container's channel count, Exo knows it only
 * when the extractor resolved one -- and a track with no codec information is the
 * normal case for a subtitle. **A count, not a layout**: neither engine reports
 * speaker positions, so 6 means "six channels" and never "5.1".
 */
data class NativeTrack(
    val id: String,
    val index: Int,
    val language: String?,
    val title: String?,
    val isExternal: Boolean = false,
    /** The engine's own codec tag for the track, e.g. `eac3`, `aac` or `audio/eac3`. */
    val codec: String? = null,
    /** How many channels the track carries, or `null` when the engine does not say. */
    val channelCount: Int? = null,
)

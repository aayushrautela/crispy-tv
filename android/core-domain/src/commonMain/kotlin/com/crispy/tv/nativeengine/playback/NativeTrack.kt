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
 */
data class NativeTrack(
    val id: String,
    val index: Int,
    val language: String?,
    val title: String?,
    val isExternal: Boolean = false,
)

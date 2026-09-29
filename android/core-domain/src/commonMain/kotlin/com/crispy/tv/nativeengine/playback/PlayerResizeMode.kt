package com.crispy.tv.nativeengine.playback

/**
 * How the video surface is fitted to the window.
 *
 * Moved out of `:android:native-engine` for the same reason as
 * [NativePlaybackEnginePreference]: this type is part of
 * [com.crispy.tv.settings.PlaybackSettings], and a plain `com.android.library`
 * cannot be referenced from a KMP `commonMain` at all, so its old location
 * pinned every settings type that mentioned it to `androidMain` regardless of
 * what the code itself touched.
 *
 * The package deliberately did not change, so the seven other files that use
 * this enum needed no import edit.
 */
enum class PlayerResizeMode {
    Fit,
    Zoom,
    ;

    fun next(): PlayerResizeMode =
        when (this) {
            Fit -> Zoom
            Zoom -> Fit
        }

    val label: String
        get() =
            when (this) {
                Fit -> "Fit"
                Zoom -> "Zoom"
            }
}

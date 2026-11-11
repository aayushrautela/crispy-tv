package com.crispy.tv.nativeengine.playback

/**
 * How the video surface is fitted to the window.
 *
 * Moved out of `:android:native-engine` because
 * this type is part of
 * [com.crispy.tv.settings.PlaybackSettings], and a plain `com.android.library`
 * cannot be referenced from a KMP `commonMain` at all, so its old location
 * pinned every settings type that mentioned it to `androidMain` regardless of
 * what the code itself touched.
 *
 * The package deliberately did not change, so the seven other files that use
 * this enum needed no import edit.
 */
enum class PlayerResizeMode {
    /** The whole picture is visible; the window's remainder is black. */
    Fit,

    /**
     * The picture fills the window and the overflow is cropped, the way
     * ExoPlayer's `RESIZE_MODE_ZOOM` behaved. The crop happens in the UI
     * layer, because the tunneled `mediacodec_embed` output ignores mpv's
     * own crop options.
     */
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

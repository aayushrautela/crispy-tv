package com.crispy.tv.nativeengine.playback

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

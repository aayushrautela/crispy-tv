package com.crispy.tv.nativeengine.playback

enum class PlayerResizeMode {
    Fit,
    Fill,
    Zoom,
    ;

    fun next(): PlayerResizeMode =
        when (this) {
            Fit -> Fill
            Fill -> Zoom
            Zoom -> Fit
        }

    val label: String
        get() =
            when (this) {
                Fit -> "Fit"
                Fill -> "Fill"
                Zoom -> "Zoom"
            }
}

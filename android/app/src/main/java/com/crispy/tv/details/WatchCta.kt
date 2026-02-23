package com.crispy.tv.details

internal enum class WatchCtaKind {
    WATCH,
    CONTINUE,
    REWATCH,
}

internal enum class WatchCtaIcon {
    PLAY,
    REPLAY,
}

internal data class WatchCta(
    val kind: WatchCtaKind = WatchCtaKind.WATCH,
    val label: String = "Watch now",
    val icon: WatchCtaIcon = WatchCtaIcon.PLAY,
    // If set, UI can render an "Ends at" time using now + remainingMinutes.
    val remainingMinutes: Int? = null,
    // If set, UI can render "Last watched on".
    val lastWatchedAtEpochMs: Long? = null,
)

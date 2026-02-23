package com.crispy.tv.details

enum class WatchCtaKind {
    WATCH,
    CONTINUE,
    REWATCH,
}

enum class WatchCtaIcon {
    PLAY,
    REPLAY,
}

data class WatchCta(
    val kind: WatchCtaKind = WatchCtaKind.WATCH,
    val label: String = "Watch now",
    val icon: WatchCtaIcon = WatchCtaIcon.PLAY,
    // If set, UI can render an "Ends at" time using now + remainingMinutes.
    val remainingMinutes: Int? = null,
    // If set, UI can render "Last watched on".
    val lastWatchedAtEpochMs: Long? = null,
)

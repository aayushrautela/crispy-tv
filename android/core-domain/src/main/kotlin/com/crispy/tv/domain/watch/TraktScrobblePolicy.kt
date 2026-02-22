package com.crispy.tv.domain.watch

enum class TraktScrobbleStage {
    START,
    STOP
}

data class TraktScrobbleDecision(
    val endpoint: String,
    val marksWatched: Boolean,
    val updatesPlaybackProgress: Boolean
)

fun decideTraktScrobble(stage: TraktScrobbleStage, progressPercent: Double): TraktScrobbleDecision {
    val progress = progressPercent.coerceIn(0.0, 100.0)
    if (stage == TraktScrobbleStage.START) {
        return TraktScrobbleDecision(
            endpoint = "/scrobble/start",
            marksWatched = false,
            updatesPlaybackProgress = false
        )
    }

    val marksWatched = progress >= 80.0
    return TraktScrobbleDecision(
        endpoint = "/scrobble/stop",
        marksWatched = marksWatched,
        updatesPlaybackProgress = !marksWatched
    )
}

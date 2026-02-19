package com.crispy.rewrite.domain.player

enum class PlayerIntent {
    PLAY,
    PAUSE
}

enum class PlayerPhase {
    IDLE,
    BOOTING_TORRENT,
    POLLING_LOCALHOST,
    LOADING_MEDIA,
    SEEKING,
    BUFFERING,
    READY,
    ENDED,
    ERROR
}

data class PlayerState(
    val sessionId: String,
    val intent: PlayerIntent,
    val phase: PlayerPhase,
    val engine: String,
    val pendingVersion: Long,
    val updatedAtMs: Long
)

fun initialPlayerState(sessionId: String, nowMs: Long): PlayerState {
    return PlayerState(
        sessionId = sessionId,
        intent = PlayerIntent.PLAY,
        phase = PlayerPhase.IDLE,
        engine = "exo",
        pendingVersion = 0,
        updatedAtMs = nowMs
    )
}

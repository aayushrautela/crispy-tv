package com.crispy.tv.domain.player

sealed interface PlayerAction {
    data class OpenHttp(val engine: String?) : PlayerAction
    data class OpenTorrent(val engine: String?) : PlayerAction
    data object TorrentStreamResolved : PlayerAction
    data object NativeFirstFrame : PlayerAction
    data object NativeBuffering : PlayerAction
    data object NativeReady : PlayerAction
    data object NativeEnded : PlayerAction
    data object NativeCodecError : PlayerAction
    data object UserIntentPlay : PlayerAction
    data object UserIntentPause : PlayerAction
}

fun reducePlayerState(
    state: PlayerState,
    action: PlayerAction,
    nowMs: Long
): PlayerState {
    return when (action) {
        is PlayerAction.OpenHttp -> state.copy(
            intent = PlayerIntent.PLAY,
            phase = PlayerPhase.LOADING_MEDIA,
            engine = action.engine ?: state.engine,
            pendingVersion = state.pendingVersion + 1,
            updatedAtMs = nowMs
        )

        is PlayerAction.OpenTorrent -> state.copy(
            intent = PlayerIntent.PLAY,
            phase = PlayerPhase.BOOTING_TORRENT,
            engine = action.engine ?: state.engine,
            pendingVersion = state.pendingVersion + 1,
            updatedAtMs = nowMs
        )

        PlayerAction.TorrentStreamResolved -> state.copy(
            phase = PlayerPhase.LOADING_MEDIA,
            updatedAtMs = nowMs
        )

        PlayerAction.NativeFirstFrame,
        PlayerAction.NativeReady -> state.copy(
            phase = PlayerPhase.READY,
            updatedAtMs = nowMs
        )

        PlayerAction.NativeBuffering -> state.copy(
            phase = PlayerPhase.BUFFERING,
            updatedAtMs = nowMs
        )

        PlayerAction.NativeEnded -> state.copy(
            phase = PlayerPhase.ENDED,
            updatedAtMs = nowMs
        )

        PlayerAction.NativeCodecError -> state.copy(
            phase = PlayerPhase.LOADING_MEDIA,
            engine = if (state.engine.equals("exo", ignoreCase = true)) "vlc" else state.engine,
            updatedAtMs = nowMs
        )

        PlayerAction.UserIntentPlay -> state.copy(
            intent = PlayerIntent.PLAY,
            pendingVersion = state.pendingVersion + 1,
            updatedAtMs = nowMs
        )

        PlayerAction.UserIntentPause -> state.copy(
            intent = PlayerIntent.PAUSE,
            pendingVersion = state.pendingVersion + 1,
            updatedAtMs = nowMs
        )
    }
}

fun PlayerIntent.toContractValue(): String = when (this) {
    PlayerIntent.PLAY -> "play"
    PlayerIntent.PAUSE -> "pause"
}

fun PlayerPhase.toContractValue(): String = when (this) {
    PlayerPhase.IDLE -> "idle"
    PlayerPhase.BOOTING_TORRENT -> "booting_torrent"
    PlayerPhase.POLLING_LOCALHOST -> "polling_localhost"
    PlayerPhase.LOADING_MEDIA -> "loading_media"
    PlayerPhase.SEEKING -> "seeking"
    PlayerPhase.BUFFERING -> "buffering"
    PlayerPhase.READY -> "ready"
    PlayerPhase.ENDED -> "ended"
    PlayerPhase.ERROR -> "error"
}

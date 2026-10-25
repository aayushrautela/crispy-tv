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
    data object NativeFatalError : PlayerAction
    data object UserIntentPlay : PlayerAction
    data object UserIntentPause : PlayerAction
}

/**
 * Whether this action opens a new source.
 *
 * Opening a source is the only thing that leaves the terminal `error` phase.
 */
private fun PlayerAction.opensNewSource(): Boolean {
    return this is PlayerAction.OpenHttp || this is PlayerAction.OpenTorrent
}

/**
 * The playback state machine, mirrored by Swift's `reducePlayerState` in
 * `ios/ContractRunner`. The two must change together: the `player_machine`
 * contract suite exercises both against the same fixtures.
 *
 * Three rules make the `error` phase meaningful (`contract_version` 3):
 *
 * 1. A codec error falls back to the `mpv` engine **only** while the active
 *    engine is `exo`. On a platform whose engine already is `mpv` there is no
 *    second engine to fall back to, so the failure is terminal.
 * 2. [PlayerAction.NativeFatalError] is the generic "the engine failed and
 *    cannot recover" event, kept distinct from a codec error so a caller can
 *    report an mpv failure without mislabelling it.
 * 3. The `error` phase is terminal: once the machine has failed, only opening
 *    a new source changes the state. A frame that was already in flight when
 *    the failure arrived must not resurrect the session.
 */
fun reducePlayerState(
    state: PlayerState,
    action: PlayerAction,
    nowMs: Long
): PlayerState {
    if (state.phase == PlayerPhase.ERROR && !action.opensNewSource()) {
        return state.copy(updatedAtMs = nowMs)
    }

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

        // A codec error falls back to the `mpv` engine only while the active
        // engine is `exo`. Any other engine is the last one there is, so the
        // failure is terminal.
        PlayerAction.NativeCodecError -> if (state.engine.equals("exo", ignoreCase = true)) {
            state.copy(
                phase = PlayerPhase.LOADING_MEDIA,
                engine = "mpv",
                updatedAtMs = nowMs
            )
        } else {
            state.copy(
                phase = PlayerPhase.ERROR,
                updatedAtMs = nowMs
            )
        }

        PlayerAction.NativeFatalError -> state.copy(
            phase = PlayerPhase.ERROR,
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

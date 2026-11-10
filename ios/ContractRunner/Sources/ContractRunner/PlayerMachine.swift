import Foundation

public enum PlayerIntent: String {
    case play
    case pause
}

public enum PlayerPhase: String {
    case idle
    case booting_torrent
    case polling_localhost
    case loading_media
    case seeking
    case buffering
    case ready
    case ended
    case error
}

public struct PlayerState {
    public var sessionId: String
    public var intent: PlayerIntent
    public var phase: PlayerPhase
    public var engine: String
    public var pendingVersion: Int
    public var updatedAtMs: Int64

    public init(
        sessionId: String,
        intent: PlayerIntent,
        phase: PlayerPhase,
        engine: String,
        pendingVersion: Int,
        updatedAtMs: Int64
    ) {
        self.sessionId = sessionId
        self.intent = intent
        self.phase = phase
        self.engine = engine
        self.pendingVersion = pendingVersion
        self.updatedAtMs = updatedAtMs
    }
}

public enum PlayerAction {
    case openHttp(engine: String?)
    case openTorrent(engine: String?)
    case torrentStreamResolved
    case nativeFirstFrame
    case nativeBuffering
    case nativeReady
    case nativeEnded
    case nativeCodecError
    case nativeFatalError
    case userIntentPlay
    case userIntentPause
}

extension PlayerAction {
    /// Opening a source is the only thing that leaves the terminal `error` phase.
    var opensNewSource: Bool {
        switch self {
        case .openHttp, .openTorrent:
            return true
        default:
            return false
        }
    }
}

public func initialPlayerState(sessionId: String, nowMs: Int64) -> PlayerState {
    PlayerState(
        sessionId: sessionId,
        intent: .play,
        phase: .idle,
        engine: "mpv",
        pendingVersion: 0,
        updatedAtMs: nowMs
    )
}

/// The playback state machine, mirrored by Kotlin's `reducePlayerState` in
/// `android/core-domain`. The two must change together: the `player_machine`
/// contract suite exercises both against the same fixtures.
///
/// Two rules make the `error` phase meaningful (`contract_version` 4):
///
/// 1. `nativeCodecError` is terminal on every engine: mpv is the only engine
///    there is, so there is no second engine to fall back to and the phase
///    becomes `error` with the engine unchanged.
/// 2. `nativeFatalError` is the generic "the engine failed and cannot recover"
///    event, kept distinct from a codec error so a caller can report a failure
///    without mislabelling it.
///
/// The `error` phase is terminal: once the machine has failed, only opening
/// a new source changes the state. A frame that was already in flight when
/// the failure arrived must not resurrect the session.
public func reducePlayerState(_ state: PlayerState, action: PlayerAction, nowMs: Int64) -> PlayerState {
    if state.phase == .error, !action.opensNewSource {
        var frozen = state
        frozen.updatedAtMs = nowMs
        return frozen
    }

    var next = state
    next.updatedAtMs = nowMs

    switch action {
    case .openHttp(let engine):
        next.intent = .play
        next.phase = .loading_media
        next.engine = engine ?? next.engine
        next.pendingVersion += 1

    case .openTorrent(let engine):
        next.intent = .play
        next.phase = .booting_torrent
        next.engine = engine ?? next.engine
        next.pendingVersion += 1

    case .torrentStreamResolved:
        next.phase = .loading_media

    case .nativeFirstFrame, .nativeReady:
        next.phase = .ready

    case .nativeBuffering:
        next.phase = .buffering

    case .nativeEnded:
        next.phase = .ended

    case .nativeCodecError:
        // A codec error is terminal on every engine: mpv is the only engine
        // left, so there is nothing to fall back to.
        next.phase = .error

    case .nativeFatalError:
        next.phase = .error

    case .userIntentPlay:
        next.intent = .play
        next.pendingVersion += 1

    case .userIntentPause:
        next.intent = .pause
        next.pendingVersion += 1
    }

    return next
}

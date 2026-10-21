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
        engine: "exo",
        pendingVersion: 0,
        updatedAtMs: nowMs
    )
}

/// The playback state machine, mirrored by Kotlin's `reducePlayerState` in
/// `android/core-domain`. The two must change together: the `player_machine`
/// contract suite exercises both against the same fixtures.
///
/// Three rules make the `error` phase meaningful (`contract_version` 3):
///
/// 1. A codec error falls back to the `mpv` engine **only** while the active
///    engine is `exo`. On a platform whose engine already is `mpv` there is no
///    second engine to fall back to, so the failure is terminal.
/// 2. `nativeFatalError` is the generic "the engine failed and cannot recover"
///    event, kept distinct from a codec error so a caller can report an mpv
///    failure without mislabelling it.
/// 3. The `error` phase is terminal: once the machine has failed, only opening
///    a new source changes the state. A frame that was already in flight when
///    the failure arrived must not resurrect the session.
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
        // A codec error falls back to the `mpv` engine only while the active
        // engine is `exo`. Any other engine is the last one there is, so the
        // failure is terminal.
        if next.engine.lowercased() == "exo" {
            next.phase = .loading_media
            next.engine = "mpv"
        } else {
            next.phase = .error
        }

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

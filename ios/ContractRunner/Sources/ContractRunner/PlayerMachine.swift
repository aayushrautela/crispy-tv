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
    case userIntentPlay
    case userIntentPause
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

public func reducePlayerState(_ state: PlayerState, action: PlayerAction, nowMs: Int64) -> PlayerState {
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
        next.phase = .loading_media
        if next.engine.lowercased() == "exo" {
            next.engine = "vlc"
        }

    case .userIntentPlay:
        next.intent = .play
        next.pendingVersion += 1

    case .userIntentPause:
        next.intent = .pause
        next.pendingVersion += 1
    }

    return next
}

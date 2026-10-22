import ContractRunner
import Foundation
import Observation

/// The player session: the state the chrome renders, and the only thing that
/// talks to the contract's `player_machine` reducer.
///
/// The session owns two kinds of state. The contract state — phase, intent,
/// engine — is whatever `reducePlayerState` says it is, and only the reducer
/// writes it. The transport state — position, duration, buffered range, the
/// last error — is not part of the contract, because the contract machine has
/// no vocabulary for a playhead.
@MainActor
@Observable
public final class PlayerSession {
    /// The contract state. Written only by `reduce`.
    public private(set) var state: PlayerState
    public private(set) var positionMs: Int64 = 0
    public private(set) var durationMs: Int64 = 0
    public private(set) var bufferedMs: Int64 = 0
    /// The tracks the media carries, and the one that plays. Not contract
    /// state: the contract machine has no vocabulary for a track.
    public private(set) var tracks: [PlaybackTrack] = []
    /// The media that is open, so the session can play it again.
    public private(set) var source: PlaybackSource?
    /// The message of the last failure, kept beside the contract state
    /// because the contract has no field for it.
    public private(set) var lastErrorMessage: String?
    /// Called after the playhead is moved on purpose.
    ///
    /// The session does not report progress itself — it only says that the
    /// playhead jumped, so a progress reporter can arm its settle window
    /// instead of persisting the engine's transient position of 0.
    @ObservationIgnored public var onSeek: (@MainActor (Int64) -> Void)?

    @ObservationIgnored private let engine: PlaybackEngine
    @ObservationIgnored private let nowMs: () -> Int64
    @ObservationIgnored private let observer: EventObserver

    /// - Parameters:
    ///   - engine: the engine that plays the media.
    ///   - sessionId: the contract's session identifier.
    ///   - nowMs: the clock the reducer stamps its transitions with. Injecting
    ///     it keeps the session deterministic under test.
    public init(
        engine: PlaybackEngine,
        sessionId: String = UUID().uuidString,
        nowMs: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.engine = engine
        self.nowMs = nowMs
        self.observer = EventObserver()
        var state = initialPlayerState(sessionId: sessionId, nowMs: nowMs())
        // `initialPlayerState` pins Android's default engine. This session
        // only ever drives the engine it was built with, so the identifier is
        // corrected here rather than by a new contract rule.
        state.engine = engine.engineId
        self.state = state
        observeEvents()
    }

    /// Start playing `source` on the engine.
    public func open(_ source: PlaybackSource) {
        positionMs = source.resumePositionMs
        durationMs = 0
        bufferedMs = 0
        tracks = []
        lastErrorMessage = nil
        self.source = source
        reduce(.openHttp(engine: engine.engineId))
        engine.open(source)
    }

    /// Play the media again, from the beginning.
    ///
    /// The contract has no "restart" action, and its `ended` phase would
    /// stay `ended` if the session only seeked. Opening the media again is
    /// the one thing that leaves a finished or failed session.
    public func replay() {
        guard let source else { return }
        open(source.resuming(at: 0))
    }

    public func play() {
        reduce(.userIntentPlay)
        engine.play()
    }

    public func pause() {
        reduce(.userIntentPause)
        engine.pause()
    }

    /// Move the playhead. The contract has no seek action — `seeking` is a
    /// phase it never reaches — so this is transport state plus an engine
    /// command.
    public func seek(to positionMs: Int64) {
        self.positionMs = positionMs
        engine.seek(to: positionMs)
        onSeek?(positionMs)
    }

    /// Choose the track that plays, or turn a kind of track off. The engine
    /// reports the new list back through a `tracksChanged` event.
    public func selectTrack(_ selection: PlaybackTrackSelection) {
        engine.selectTrack(selection)
    }

    /// Release the engine and stop observing it. Called when the player is
    /// dismissed.
    public func close() {
        observer.cancel()
        engine.shutdown()
    }

    private func observeEvents() {
        let stream = engine.makeEventStream()
        observer.task = Task { [weak self] in
            for await event in stream {
                guard let self else { return }
                self.handle(event)
            }
        }
    }

    private func handle(_ event: PlaybackEvent) {
        switch event {
        case .positionChanged(let positionMs, let durationMs, let bufferedMs):
            self.positionMs = positionMs
            self.durationMs = durationMs
            self.bufferedMs = bufferedMs
        case .firstFrame:
            reduce(.nativeFirstFrame)
        case .ready:
            reduce(.nativeReady)
        case .buffering:
            reduce(.nativeBuffering)
        case .ended:
            reduce(.nativeEnded)
        case .tracksChanged(let tracks):
            self.tracks = tracks
        case .codecError:
            reduce(.nativeCodecError)
        case .failed(let message):
            lastErrorMessage = message
            reduce(.nativeFatalError)
        }
    }

    private func reduce(_ action: PlayerAction) {
        state = reducePlayerState(state, action: action, nowMs: nowMs())
    }
}

/// Holds the task that observes the engine's events, so that the task is
/// cancelled when the session is deallocated. A separate object because a
/// `deinit` may not touch actor-isolated state.
private final class EventObserver {
    var task: Task<Void, Never>?

    func cancel() {
        task?.cancel()
        task = nil
    }

    deinit {
        task?.cancel()
    }
}

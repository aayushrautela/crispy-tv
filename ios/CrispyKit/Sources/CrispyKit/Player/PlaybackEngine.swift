import Foundation

/// What an engine reports back while it plays.
///
/// The set is deliberately small: every case maps onto exactly one
/// `PlayerAction` of the `player_machine` contract, so the session never has
/// to guess what a phase means. Position updates are not contract events —
/// they are transport ticks the chrome reads.
public enum PlaybackEvent: Equatable, Sendable {
    case positionChanged(positionMs: Int64, durationMs: Int64, bufferedMs: Int64)
    /// The first frame is on screen.
    case firstFrame
    /// The engine is ready but has not started producing frames yet.
    case ready
    case buffering
    case ended
    /// The media carries tracks, and one of them is the one that plays.
    /// The contract has no vocabulary for tracks, so this is transport state.
    case tracksChanged([PlaybackTrack])
    /// The media could not be decoded. On a platform whose only engine is
    /// `mpv` this is terminal — there is no second engine to fall back to.
    case codecError
    /// The engine failed for a reason it cannot recover from.
    case failed(String)
}

/// The seam between the player session and a playback engine.
///
/// The iOS engine is libmpv; the session is written against this protocol so
/// it can be exercised without an engine, and so the engine can be replaced
/// without touching the session or the chrome.
public protocol PlaybackEngine: AnyObject {
    /// The engine identifier the contract machine records, for example `mpv`.
    /// The contract's codec-error fallback is written in terms of `exo`, so
    /// this value decides whether a failure is recoverable.
    var engineId: String { get }

    /// The event stream for this engine. Called once by the session that owns
    /// the engine; an `AsyncStream` can only be consumed once.
    func makeEventStream() -> AsyncStream<PlaybackEvent>

    /// Start playing `source`. Replaces whatever was playing.
    func open(_ source: PlaybackSource)
    func play()
    func pause()
    func seek(to positionMs: Int64)
    /// Choose the track that plays, or turn a kind of track off.
    func selectTrack(_ selection: PlaybackTrackSelection)
    /// Stop and release the engine. Called when the player is dismissed.
    func shutdown()
}

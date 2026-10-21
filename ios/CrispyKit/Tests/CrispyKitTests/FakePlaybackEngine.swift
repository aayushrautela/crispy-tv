import Foundation
@testable import CrispyKit

/// A `PlaybackEngine` double: it records the commands the session issues and
/// lets a test push events back. The session is written against the protocol,
/// so a session that behaves here is not relying on a real engine.
final class FakePlaybackEngine: PlaybackEngine {
    let engineId: String

    private let stream: AsyncStream<PlaybackEvent>
    private let continuation: AsyncStream<PlaybackEvent>.Continuation

    private(set) var openedSources: [PlaybackSource] = []
    private(set) var playCount = 0
    private(set) var pauseCount = 0
    private(set) var seekPositionsMs: [Int64] = []
    private(set) var shutdownCount = 0

    init(engineId: String = "mpv") {
        self.engineId = engineId
        let pair = AsyncStream.makeStream(of: PlaybackEvent.self)
        self.stream = pair.stream
        self.continuation = pair.continuation
    }

    func makeEventStream() -> AsyncStream<PlaybackEvent> {
        stream
    }

    func open(_ source: PlaybackSource) {
        openedSources.append(source)
    }

    func play() {
        playCount += 1
    }

    func pause() {
        pauseCount += 1
    }

    func seek(to positionMs: Int64) {
        seekPositionsMs.append(positionMs)
    }

    func shutdown() {
        shutdownCount += 1
    }

    func emit(_ event: PlaybackEvent) {
        continuation.yield(event)
    }
}

/// A clock the test advances by hand, so the timestamps the session stamps
/// its transitions with are deterministic.
final class ManualClock {
    private(set) var nowMs: Int64 = 0

    func advance(by deltaMs: Int64) {
        nowMs += deltaMs
    }
}

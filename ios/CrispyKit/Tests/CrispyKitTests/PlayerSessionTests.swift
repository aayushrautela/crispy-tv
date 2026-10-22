import ContractRunner
import XCTest
@testable import CrispyKit

@MainActor
final class PlayerSessionTests: XCTestCase {
    func testOpenStartsTheEngineAndMovesToLoadingMedia() {
        let engine = FakePlaybackEngine()
        let session = makeSession(engine: engine)

        XCTAssertEqual(session.state.phase, .idle)
        XCTAssertEqual(session.state.engine, "mpv")

        session.open(PlaybackSource(url: makeURL("https://example.com/stream.mkv")))

        XCTAssertEqual(session.state.phase, .loading_media)
        XCTAssertEqual(session.state.engine, "mpv")
        XCTAssertEqual(engine.openedSources.count, 1)
        XCTAssertEqual(engine.openedSources.first?.url, makeURL("https://example.com/stream.mkv"))
    }

    func testOpenAppliesTheResumePosition() {
        let engine = FakePlaybackEngine()
        let session = makeSession(engine: engine)

        session.open(
            PlaybackSource(url: makeURL("https://example.com/stream.mkv"), resumePositionMs: 42_000)
        )

        XCTAssertEqual(session.positionMs, 42_000)
    }

    func testEngineEventsDriveTheContractPhases() async {
        let engine = FakePlaybackEngine()
        let session = makeSession(engine: engine)
        session.open(PlaybackSource(url: makeURL("https://example.com/stream.mkv")))

        engine.emit(.firstFrame)
        await waitUntil("first frame reaches the ready phase") { session.state.phase == .ready }

        engine.emit(.buffering)
        await waitUntil("buffering reaches the buffering phase") { session.state.phase == .buffering }

        engine.emit(.ended)
        await waitUntil("end reaches the ended phase") { session.state.phase == .ended }
    }

    func testCodecErrorOnMpvIsTerminal() async {
        let engine = FakePlaybackEngine()
        let session = makeSession(engine: engine)
        session.open(PlaybackSource(url: makeURL("https://example.com/stream.mkv")))

        engine.emit(.codecError)
        await waitUntil("the codec error reaches the error phase") { session.state.phase == .error }
        XCTAssertEqual(session.state.engine, "mpv")

        // A frame that was already in flight must not resurrect the session.
        engine.emit(.firstFrame)
        await drainEvents()
        XCTAssertEqual(session.state.phase, .error)
    }

    func testFatalErrorIsTerminalAndKeepsTheMessage() async {
        let engine = FakePlaybackEngine()
        let session = makeSession(engine: engine)
        session.open(PlaybackSource(url: makeURL("https://example.com/stream.mkv")))

        engine.emit(.failed("decoder crashed"))
        await waitUntil("the failure reaches the error phase") { session.state.phase == .error }

        XCTAssertEqual(session.lastErrorMessage, "decoder crashed")
    }

    func testTerminalErrorFreezesTheIntent() async {
        let engine = FakePlaybackEngine()
        let session = makeSession(engine: engine)
        session.open(PlaybackSource(url: makeURL("https://example.com/stream.mkv")))
        session.pause()

        engine.emit(.failed("decoder crashed"))
        await waitUntil("the failure reaches the error phase") { session.state.phase == .error }

        session.play()

        XCTAssertEqual(session.state.intent, .pause)
    }

    func testPositionEventsUpdateTheTransportState() async {
        let engine = FakePlaybackEngine()
        let session = makeSession(engine: engine)

        engine.emit(.positionChanged(positionMs: 42_000, durationMs: 3_600_000, bufferedMs: 120_000))
        await waitUntil("the position reaches the session") { session.positionMs == 42_000 }

        XCTAssertEqual(session.durationMs, 3_600_000)
        XCTAssertEqual(session.bufferedMs, 120_000)
    }

    func testPlayAndPauseAreForwardedAndChangeTheIntent() {
        let engine = FakePlaybackEngine()
        let session = makeSession(engine: engine)
        session.open(PlaybackSource(url: makeURL("https://example.com/stream.mkv")))

        session.pause()
        XCTAssertEqual(session.state.intent, .pause)
        XCTAssertEqual(engine.pauseCount, 1)

        session.play()
        XCTAssertEqual(session.state.intent, .play)
        XCTAssertEqual(engine.playCount, 1)
    }

    func testSeekMovesThePlayheadAndIsForwarded() {
        let engine = FakePlaybackEngine()
        let session = makeSession(engine: engine)

        session.seek(to: 90_000)

        XCTAssertEqual(session.positionMs, 90_000)
        XCTAssertEqual(engine.seekPositionsMs, [90_000])
    }

    func testCloseShutsTheEngineDown() {
        let engine = FakePlaybackEngine()
        let session = makeSession(engine: engine)

        session.close()

        XCTAssertEqual(engine.shutdownCount, 1)
    }

    func testTracksEventPopulatesTheSessionTracks() async {
        let engine = FakePlaybackEngine()
        let session = makeSession(engine: engine)
        let track = PlaybackTrack(
            id: "2",
            kind: .audio,
            title: "English",
            language: "eng",
            isSelected: true
        )

        engine.emit(.tracksChanged([track]))
        await waitUntil("the tracks reach the session") { session.tracks.count == 1 }

        guard let received = session.tracks.first else {
            return XCTFail("The session has no track")
        }
        XCTAssertEqual(received.id, "2")
        XCTAssertEqual(received.kind, .audio)
        XCTAssertEqual(received.language, "eng")
        XCTAssertTrue(received.isSelected)
    }

    func testSelectTrackIsForwardedToTheEngine() {
        let engine = FakePlaybackEngine()
        let session = makeSession(engine: engine)
        let track = PlaybackTrack(id: "3", kind: .subtitle, title: "Deutsch")

        session.selectTrack(.track(track))
        session.selectTrack(.disabled(.subtitle))

        XCTAssertEqual(engine.trackSelections.count, 2)
        XCTAssertEqual(engine.trackSelections, [.track(track), .disabled(.subtitle)])
    }

    func testAChosenTrackIsReportedToTheCaller() {
        let engine = FakePlaybackEngine()
        let session = makeSession(engine: engine)
        var reported: [PlaybackTrackSelection] = []
        session.onTrackSelected = { reported.append($0) }
        let track = PlaybackTrack(id: "3", kind: .audio, title: "English", language: "eng")

        session.selectTrack(.track(track))
        session.selectTrack(.disabled(.subtitle))

        XCTAssertEqual(reported, [.track(track), .disabled(.subtitle)])
    }

    func testOpenClearsTheTracksOfThePreviousMedia() async {
        let engine = FakePlaybackEngine()
        let session = makeSession(engine: engine)

        engine.emit(.tracksChanged([PlaybackTrack(id: "1", kind: .audio)]))
        await waitUntil("the tracks reach the session") { session.tracks.count == 1 }

        session.open(PlaybackSource(url: makeURL("https://example.com/other.mkv")))

        XCTAssertTrue(session.tracks.isEmpty)
    }

    func testReplayReopensTheMediaFromTheBeginning() {
        let engine = FakePlaybackEngine()
        let session = makeSession(engine: engine)
        session.open(
            PlaybackSource(url: makeURL("https://example.com/stream.mkv"), resumePositionMs: 42_000)
        )

        session.replay()

        XCTAssertEqual(session.positionMs, 0)
        XCTAssertEqual(session.state.phase, .loading_media)
        XCTAssertEqual(engine.openedSources.count, 2)
        XCTAssertEqual(engine.openedSources.last?.resumePositionMs, 0)
    }

    func testReplayLeavesTheTerminalErrorPhase() async {
        let engine = FakePlaybackEngine()
        let session = makeSession(engine: engine)
        session.open(PlaybackSource(url: makeURL("https://example.com/stream.mkv")))

        engine.emit(.failed("decoder crashed"))
        await waitUntil("the failure reaches the error phase") { session.state.phase == .error }

        session.replay()

        XCTAssertEqual(session.state.phase, .loading_media)
    }

    private func makeSession(engine: PlaybackEngine, clock: ManualClock = ManualClock()) -> PlayerSession {
        PlayerSession(engine: engine, sessionId: "session-1", nowMs: { clock.nowMs })
    }

    /// Waits for the session's event loop to pick an event up. The loop is a
    /// task on the main actor, so yielding is what gives it a turn.
    private func waitUntil(
        _ description: String,
        _ condition: @MainActor () -> Bool
    ) async {
        for _ in 0..<100 {
            if condition() { return }
            await Task.yield()
        }
        XCTFail("Timed out waiting until \(description)")
    }

    /// Gives the event loop a chance to run, for the cases that assert that
    /// something did *not* happen.
    private func drainEvents() async {
        for _ in 0..<10 {
            await Task.yield()
        }
    }

    private func makeURL(_ string: String) -> URL {
        guard let url = URL(string: string) else {
            preconditionFailure("Invalid test URL: \(string)")
        }
        return url
    }
}

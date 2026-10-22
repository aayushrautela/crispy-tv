import Foundation
import XCTest
@testable import CrispyKit

/// The reporter's tests.
///
/// The reporter is written against two clocks, and the tests hand it two
/// clocks it can move by hand: the wall clock the events are stamped with and
/// the monotonic clock the settle window and the persist interval are measured
/// on. Nothing here waits on real time.
@MainActor
final class PlaybackProgressReporterTests: XCTestCase {
    private let identity = PlaybackIdentity(
        itemId: "tt1234567",
        title: "The Episode",
        showTitle: "The Show",
        season: 2,
        episode: 5
    )

    func testTheFirstSettledPollReportsAStart() async {
        let recorder = RecordingEventSender()
        let clock = ManualProgressClock()
        let reporter = makeReporter(recorder: recorder, clock: clock)

        reporter.syncWatchHistory(
            identity: identity,
            positionMs: 5_000,
            durationMs: 120_000,
            isPlaying: true
        )
        await drainEvents()

        XCTAssertEqual(recorder.inputs.count, 1)
        let input = recorder.inputs.first
        XCTAssertEqual(input?.eventType, PlaybackEventType.progress.rawValue)
        XCTAssertEqual(input?.itemId, "tt1234567")
        XCTAssertEqual(input?.positionSeconds, 5.0)
        XCTAssertEqual(input?.durationSeconds, 120.0)
        XCTAssertEqual(input?.seasonNumber, 2)
        XCTAssertEqual(input?.episodeNumber, 5)
        XCTAssertEqual(input?.payload["source"], "ios")
        XCTAssertEqual(input?.payload["title"], "The Episode")
        XCTAssertEqual(input?.payload["showTitle"], "The Show")
    }

    func testAPollBelowTheMinimumPositionReportsNothing() async {
        let recorder = RecordingEventSender()
        let clock = ManualProgressClock()
        let reporter = makeReporter(recorder: recorder, clock: clock)

        reporter.syncWatchHistory(
            identity: identity,
            positionMs: PlaybackProgressReporter.minProgressPositionMs - 1,
            durationMs: 120_000,
            isPlaying: true
        )
        await drainEvents()

        XCTAssertTrue(recorder.inputs.isEmpty)
    }

    func testAPollWithoutADurationReportsNothing() async {
        let recorder = RecordingEventSender()
        let clock = ManualProgressClock()
        let reporter = makeReporter(recorder: recorder, clock: clock)

        reporter.syncWatchHistory(identity: identity, positionMs: 5_000, durationMs: 0, isPlaying: true)
        await drainEvents()

        XCTAssertTrue(recorder.inputs.isEmpty)
    }

    func testAPollWithoutAnIdentityReportsNothing() async {
        let recorder = RecordingEventSender()
        let clock = ManualProgressClock()
        let reporter = makeReporter(recorder: recorder, clock: clock)

        reporter.syncWatchHistory(identity: nil, positionMs: 5_000, durationMs: 120_000, isPlaying: true)
        await drainEvents()

        XCTAssertTrue(recorder.inputs.isEmpty)
    }

    func testAProgressReportNeedsAStartFirst() async {
        let recorder = RecordingEventSender()
        let clock = ManualProgressClock()
        let reporter = makeReporter(recorder: recorder, clock: clock)

        // A paused poll is not a start, and a progress report cannot be sent
        // before the start has been sent.
        reporter.syncWatchHistory(identity: identity, positionMs: 5_000, durationMs: 120_000, isPlaying: false)
        await drainEvents()

        XCTAssertTrue(recorder.inputs.isEmpty)
    }

    func testAProgressReportWaitsForThePersistInterval() async {
        let recorder = RecordingEventSender()
        let clock = ManualProgressClock()
        let reporter = makeReporter(recorder: recorder, clock: clock)

        reporter.syncWatchHistory(identity: identity, positionMs: 5_000, durationMs: 120_000, isPlaying: true)
        await drainEvents()
        XCTAssertEqual(recorder.inputs.count, 1)

        clock.advance(by: 30_000)
        reporter.syncWatchHistory(identity: identity, positionMs: 35_000, durationMs: 120_000, isPlaying: true)
        await drainEvents()
        XCTAssertEqual(recorder.inputs.count, 1)

        clock.advance(by: 31_000)
        reporter.syncWatchHistory(identity: identity, positionMs: 66_000, durationMs: 120_000, isPlaying: true)
        await drainEvents()

        XCTAssertEqual(recorder.inputs.count, 2)
        XCTAssertEqual(recorder.inputs.last?.eventType, PlaybackEventType.progress.rawValue)
        XCTAssertEqual(recorder.inputs.last?.positionSeconds, 66.0)
    }

    func testAPausedPollReportsASnapshot() async {
        let recorder = RecordingEventSender()
        let clock = ManualProgressClock()
        let reporter = makeReporter(recorder: recorder, clock: clock)

        reporter.syncWatchHistory(identity: identity, positionMs: 5_000, durationMs: 120_000, isPlaying: true)
        await drainEvents()

        clock.advance(by: 61_000)
        reporter.syncWatchHistory(identity: identity, positionMs: 66_000, durationMs: 120_000, isPlaying: false)
        await drainEvents()

        XCTAssertEqual(recorder.inputs.count, 2)
        XCTAssertEqual(recorder.inputs.last?.eventType, PlaybackEventType.snapshot.rawValue)
    }

    func testASeekSuppressesTheReportsInsideTheSettleWindow() async {
        let recorder = RecordingEventSender()
        let clock = ManualProgressClock()
        let reporter = makeReporter(recorder: recorder, clock: clock)

        reporter.syncWatchHistory(identity: identity, positionMs: 5_000, durationMs: 120_000, isPlaying: true)
        await drainEvents()
        XCTAssertEqual(recorder.inputs.count, 1)

        reporter.scheduleProgressSyncAfterSeek()

        clock.advance(by: 100)
        reporter.syncWatchHistory(identity: identity, positionMs: 0, durationMs: 120_000, isPlaying: true)
        await drainEvents()
        XCTAssertEqual(recorder.inputs.count, 1)

        // The window has passed, so the report is pushed straight away rather
        // than waiting out the persist interval.
        clock.advance(by: 61_000)
        reporter.syncWatchHistory(identity: identity, positionMs: 61_100, durationMs: 120_000, isPlaying: true)
        await drainEvents()

        XCTAssertEqual(recorder.inputs.count, 2)
    }

    func testTheStopIsReportedOnce() async {
        let recorder = RecordingEventSender()
        let clock = ManualProgressClock()
        let reporter = makeReporter(recorder: recorder, clock: clock)

        reporter.reportPlaybackStopped(identity: identity, positionMs: 30_000, durationMs: 120_000)
        reporter.reportPlaybackStopped(identity: identity, positionMs: 30_000, durationMs: 120_000)
        await drainEvents()

        XCTAssertEqual(recorder.inputs.count, 1)
        XCTAssertEqual(recorder.inputs.first?.eventType, PlaybackEventType.snapshot.rawValue)
        XCTAssertEqual(recorder.inputs.first?.positionSeconds, 30.0)
    }

    func testACompletedStopReportsTheCompletionAndPinsThePosition() async {
        let recorder = RecordingEventSender()
        let clock = ManualProgressClock()
        let reporter = makeReporter(recorder: recorder, clock: clock)

        reporter.reportPlaybackStopped(identity: identity, positionMs: 110_000, durationMs: 120_000)
        await drainEvents()

        XCTAssertEqual(recorder.inputs.count, 1)
        XCTAssertEqual(recorder.inputs.first?.eventType, PlaybackEventType.completed.rawValue)
        // The position is pinned to the duration, so a trailing 0 from the
        // engine cannot wipe the resume point.
        XCTAssertEqual(recorder.inputs.first?.positionSeconds, 120.0)
    }

    func testAStopWithoutADurationConsumesItsOneChance() async {
        let recorder = RecordingEventSender()
        let clock = ManualProgressClock()
        let reporter = makeReporter(recorder: recorder, clock: clock)

        reporter.reportPlaybackStopped(identity: identity, positionMs: 30_000, durationMs: 0)
        reporter.reportPlaybackStopped(identity: identity, positionMs: 30_000, durationMs: 120_000)
        await drainEvents()

        XCTAssertTrue(recorder.inputs.isEmpty)
    }

    func testTheEventCarriesTheClientEventIdAndTheInstant() async {
        let recorder = RecordingEventSender()
        let clock = ManualProgressClock()
        clock.advance(by: 1_000)
        let reporter = makeReporter(recorder: recorder, clock: clock)

        reporter.syncWatchHistory(identity: identity, positionMs: 5_000, durationMs: 120_000, isPlaying: true)
        await drainEvents()

        let input = recorder.inputs.first
        XCTAssertEqual(input?.clientEventId, "playback_progress:tt1234567:2:5:1000")
        XCTAssertEqual(input?.occurredAt, "1970-01-01T00:00:01Z")
    }

    func testResettingTheReportingStateStartsOver() async {
        let recorder = RecordingEventSender()
        let clock = ManualProgressClock()
        let reporter = makeReporter(recorder: recorder, clock: clock)

        reporter.syncWatchHistory(identity: identity, positionMs: 5_000, durationMs: 120_000, isPlaying: true)
        await drainEvents()
        reporter.resetReportingState()

        // The start is reported again, because the reporting state went back
        // to "nothing has happened yet".
        reporter.syncWatchHistory(identity: identity, positionMs: 5_000, durationMs: 120_000, isPlaying: true)
        await drainEvents()

        XCTAssertEqual(recorder.inputs.count, 2)
        XCTAssertEqual(recorder.inputs.last?.eventType, PlaybackEventType.progress.rawValue)
    }

    private func makeReporter(
        recorder: RecordingEventSender,
        clock: ManualProgressClock
    ) -> PlaybackProgressReporter {
        PlaybackProgressReporter(
            sendEvent: { input in recorder.record(input) },
            appVersion: "1.2.3",
            nowMs: { clock.nowMs },
            elapsedMs: { clock.elapsedMs }
        )
    }

    /// Gives the reporter's send a chance to run. The send is a task on the
    /// main actor, so yielding is what gives it a turn.
    private func drainEvents() async {
        for _ in 0..<10 {
            await Task.yield()
        }
    }
}

/// The reporter's sender, doubled: it records the events the reporter sends.
@MainActor
final class RecordingEventSender {
    private(set) var inputs: [PlaybackEventInput] = []

    func record(_ input: PlaybackEventInput) {
        inputs.append(input)
    }
}

/// The two clocks the reporter reads, moved by hand.
final class ManualProgressClock: @unchecked Sendable {
    private(set) var nowMs: Int64 = 0
    private(set) var elapsedMs: Int64 = 0

    func advance(by deltaMs: Int64) {
        nowMs += deltaMs
        elapsedMs += deltaMs
    }
}

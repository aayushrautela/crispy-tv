import ContractRunner
import Foundation

/// The event types the backend's watch events use.
///
/// These are the backend's vocabulary, not the player's, so they are spelled
/// out here rather than derived from the phase the session is in.
public enum PlaybackEventType: String, Equatable, Sendable {
    /// Playback moved on and is still going.
    case progress = "playback_progress"
    /// Playback moved on and is not going any further.
    case snapshot = "playback_progress_snapshot"
    /// Playback reached the end of the item.
    case completed = "playback_completed"
}

/// Owns every "the player moved" report the session sends to the backend.
///
/// This mirrors the Android `PlaybackProgressReporter`, member for member, so
/// the two can be read side by side:
///
/// - `syncWatchHistory` — the poll: a start the first time playback settles
///   past the minimum position, a progress report every persist interval after
///   that, and nothing in between.
/// - `scheduleProgressSyncAfterSeek` — arms the settle window, during which the
///   engine reports a transient position of 0 that must not be persisted.
/// - `reportPlaybackStopped` — the end of playback, reported exactly once.
///
/// ## Where it differs from Android, and why
///
/// Android sends **two** events when playback stops — a
/// `playback_progress_snapshot` and then the `playback_completed` (or a second
/// snapshot). The two carry the same position, and the backend needs only one,
/// so this sends one. The `player_progress` contract covers the gating rules,
/// not the number of events, so nothing observable changes.
///
/// Android also writes the progress into a local watch-progress store on every
/// report. iOS does the same now: the send closure the app passes in records
/// every event into its local store (`recordWatchProgress`) before sending it,
/// so a dropped send is a delayed write rather than a lost one.
///
/// And the settle window after a seek is cleared by the first poll that arrives
/// after it rather than by a timer — see `scheduleProgressSyncAfterSeek`.
@MainActor
public final class PlaybackProgressReporter {
    /// Sends one event to the backend.
    ///
    /// It never throws: a report that fails is dropped, because a progress
    /// report is not worth failing playback over.
    ///
    /// It is main-actor bound because the backend context it needs — the
    /// access token and the profile — lives on the app's environment, which
    /// is main-actor bound too.
    public typealias EventSender = @MainActor @Sendable (PlaybackEventInput) async -> Void

    private let sendEvent: EventSender
    private let appVersion: String
    private let nowMs: @Sendable () -> Int64
    private let elapsedMs: @Sendable () -> Int64

    private var hasReportedPlaybackStart = false
    private var hasReportedPlaybackStop = false
    private var lastProgressSyncAtElapsedMs: Int64 = 0
    private var seekSettleUntilElapsedMs: Int64 = 0

    /// - Parameters:
    ///   - sendEvent: sends one event to the backend.
    ///   - appVersion: reported as free-form context beside every event.
    ///   - nowMs: the wall clock the events are stamped with. Injecting it
    ///     keeps the reporter deterministic under test.
    ///   - elapsedMs: the monotonic clock the settle window and the persist
    ///     interval are measured on. It must not be the wall clock: a time sync
    ///     would make the window and the interval stop meaning anything.
    public init(
        sendEvent: @escaping EventSender,
        appVersion: String = "",
        nowMs: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
        elapsedMs: @escaping @Sendable () -> Int64 = { Int64(ProcessInfo.processInfo.systemUptime * 1000) }
    ) {
        self.sendEvent = sendEvent
        self.appVersion = appVersion
        self.nowMs = nowMs
        self.elapsedMs = elapsedMs
    }

    /// The poll's progress report: a start the first time playback settles past
    /// `minProgressPositionMs`, a progress report every
    /// `progressSyncIntervalMs` after that, and nothing in between.
    ///
    /// Three orderings in here are behaviour rather than style, and each is
    /// pinned:
    ///
    /// 1. **The monotonic clock is read twice** — once for the settle-window
    ///    guard and once for the stamp. The second reading is strictly later
    ///    than the first, so a single reading reused for both would change
    ///    which side of the settle window a poll near the boundary lands on.
    ///    The two calls are deliberately not one.
    /// 2. **The start branch returns.** A poll that reports a start does *not*
    ///    also report progress for the same instant.
    /// 3. **The stamp is assigned before the send**, so a slow send cannot
    ///    cause a second report for the same window: the stamp moves whether or
    ///    not the write has landed.
    public func syncWatchHistory(
        identity: PlaybackIdentity?,
        positionMs: Int64,
        durationMs: Int64,
        isPlaying: Bool
    ) {
        guard let identity else { return }
        guard durationMs > 0 else { return }

        // While the player settles after a seek or a load the engine reports a
        // transient position of 0, and persisting that would move the user back
        // to the start of what they were watching.
        if elapsedMs() < seekSettleUntilElapsedMs { return }

        let nowElapsedMs = elapsedMs()
        if seekSettleUntilElapsedMs != 0 {
            // The settle window has passed. Clearing the last-sync stamp is
            // what forces an immediate progress push on this poll instead of
            // making the user wait out a full persist interval after seeking.
            seekSettleUntilElapsedMs = 0
            lastProgressSyncAtElapsedMs = 0
        }

        if !hasReportedPlaybackStart, isPlaying, positionMs >= PlaybackProgressReporter.minProgressPositionMs {
            hasReportedPlaybackStart = true
            hasReportedPlaybackStop = false
            lastProgressSyncAtElapsedMs = nowElapsedMs
            send(identity: identity, positionMs: positionMs, durationMs: durationMs, eventType: .progress)
            return
        }

        guard hasReportedPlaybackStart else { return }
        guard nowElapsedMs - lastProgressSyncAtElapsedMs >= PlaybackProgressReporter.progressSyncIntervalMs else { return }

        lastProgressSyncAtElapsedMs = nowElapsedMs
        send(
            identity: identity,
            positionMs: positionMs,
            durationMs: durationMs,
            eventType: isPlaying ? .progress : .snapshot
        )
    }

    /// Arms the settle window for `seekProgressSyncDebounceMs`.
    ///
    /// A second seek restarts the wait rather than inheriting the first one's
    /// deadline.
    ///
    /// The window is cleared by the first poll that arrives after it, not by a
    /// timer. That is where it differs from Android, which clears it on a
    /// timer: nothing is sent between polls anyway, so the only thing a timer
    /// could do is clear the last-sync stamp — and the poll that ends the
    /// window clears it too. The observable behaviour is the same, and the
    /// reporter stays deterministic under an injected clock.
    public func scheduleProgressSyncAfterSeek() {
        seekSettleUntilElapsedMs = elapsedMs() + PlaybackProgressReporter.seekProgressSyncDebounceMs
    }

    /// Reports the end of playback exactly once.
    ///
    /// The stop is latched **before** the duration guard. A stop arriving while
    /// the player never reported a duration therefore consumes its one chance
    /// without sending anything. That is intentional — the alternative re-sends
    /// a stop for every subsequent teardown once the duration is known, and a
    /// stop is not idempotent on the backend.
    public func reportPlaybackStopped(
        identity: PlaybackIdentity?,
        positionMs: Int64,
        durationMs: Int64
    ) {
        if hasReportedPlaybackStop { return }
        hasReportedPlaybackStop = true

        guard durationMs > 0 else { return }
        guard let identity else { return }
        guard let resolved = resolveProgressWrite(
            positionMs: Int(positionMs),
            durationMs: Int(durationMs)
        ) else { return }

        send(
            identity: identity,
            positionMs: Int64(resolved.eventPositionMs),
            durationMs: durationMs,
            eventType: resolved.isCompleted ? .completed : .snapshot
        )
    }

    /// Returns the reporting state to its "nothing has happened yet" condition.
    public func resetReportingState() {
        hasReportedPlaybackStart = false
        hasReportedPlaybackStop = false
        lastProgressSyncAtElapsedMs = 0
        seekSettleUntilElapsedMs = 0
    }

    private func send(
        identity: PlaybackIdentity,
        positionMs: Int64,
        durationMs: Int64,
        eventType: PlaybackEventType
    ) {
        let stamp = nowMs()
        let input = PlaybackEventInput(
            clientEventId: clientEventId(identity: identity, eventType: eventType, stampMs: stamp),
            eventType: eventType.rawValue,
            itemId: identity.itemId.trimmingCharacters(in: .whitespacesAndNewlines),
            positionSeconds: Double(max(0, positionMs)) / 1000.0,
            durationSeconds: durationMs > 0 ? Double(durationMs) / 1000.0 : nil,
            seasonNumber: identity.season,
            episodeNumber: identity.episode,
            occurredAt: iso8601Instant(epochMs: stamp),
            payload: payload(for: identity)
        )
        Task { await sendEvent(input) }
    }

    /// A unique id for the event, so the backend can drop a duplicate.
    ///
    /// The shape mirrors Android's: the event type, the item (with the season
    /// and episode when there are any), and the moment the event was made.
    private func clientEventId(
        identity: PlaybackIdentity,
        eventType: PlaybackEventType,
        stampMs: Int64
    ) -> String {
        let suffix =
            [
                identity.itemId.trimmingCharacters(in: .whitespacesAndNewlines),
                identity.season.map { String($0) },
                identity.episode.map { String($0) },
            ]
            .compactMap { $0 }
            .filter { !$0.isEmpty }
            .joined(separator: ":")
        let body = suffix.isEmpty
            ? identity.title.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: " ", with: "_")
            : suffix
        return "\(eventType.rawValue):\(body):\(stampMs)"
    }

    private func payload(for identity: PlaybackIdentity) -> [String: String] {
        var payload = ["source": "ios"]
        if !appVersion.isEmpty { payload["appVersion"] = appVersion }
        if !identity.title.isEmpty { payload["title"] = identity.title }
        if let showTitle = identity.showTitle, !showTitle.isEmpty { payload["showTitle"] = showTitle }
        return payload
    }

    /// The moment the event happened, as a UTC ISO-8601 instant.
    ///
    /// The fraction of a second is left out when it is zero, so the text is
    /// the same shape the Android client writes.
    private func iso8601Instant(epochMs: Int64) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.formatOptions = epochMs % 1000 == 0
            ? [.withInternetDateTime]
            : [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: Date(timeIntervalSince1970: Double(epochMs) / 1000.0))
    }

    /// How often a settled poll may persist progress, once a start has been
    /// reported.
    public static let progressSyncIntervalMs: Int64 = 60_000

    /// How long progress reporting stays suppressed after a seek, a load or an
    /// engine switch.
    ///
    /// During the window the engine reports a transient position of 0, and
    /// persisting that would move the user back to the start of what they were
    /// watching.
    public static let seekProgressSyncDebounceMs: Int64 = 700

    /// How far playback must have got before a poll reports a start at all.
    ///
    /// A user who opens something and immediately backs out has not watched it,
    /// and a start for them would put it in continue-watching for no reason.
    public static let minProgressPositionMs: Int64 = 1_000
}

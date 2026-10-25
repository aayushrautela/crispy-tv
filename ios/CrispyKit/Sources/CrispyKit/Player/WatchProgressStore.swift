import Foundation

/// One locally stored position, mirroring Android's `WatchProgress`.
public struct WatchProgress: Equatable {
    public let currentTimeSeconds: Double
    public let durationSeconds: Double
    public let lastUpdatedEpochMs: Int64
    public let remoteImdbId: String?
    public let addonId: String?

    public init(
        currentTimeSeconds: Double,
        durationSeconds: Double,
        lastUpdatedEpochMs: Int64,
        remoteImdbId: String? = nil,
        addonId: String? = nil
    ) {
        self.currentTimeSeconds = currentTimeSeconds
        self.durationSeconds = durationSeconds
        self.lastUpdatedEpochMs = lastUpdatedEpochMs
        self.remoteImdbId = remoteImdbId
        self.addonId = addonId
    }

    /// 0..100, and 0 when no duration is known.
    public func progressPercentOrZero() -> Double {
        guard durationSeconds > 0 else { return 0 }
        return (currentTimeSeconds / durationSeconds) * 100
    }
}

/// The local durability half of progress persistence, mirroring Android's
/// `WatchProgressStore`: every progress report is written HERE first, before
/// any network send, so a dropped send became a delayed write instead of a
/// lost one. `location` today is `UserDefaults`, standing in for Android's
/// `KeyValueStore`.
///
/// Mirrored now is exactly what iOS callers read and write: the position and
/// the content duration. Android carries more around the same store —
/// tombstones, the continue-watching removal list, a sorted all-entries scan
/// for its continue-watching planner, and the debounced change notifications —
/// and each of those lands with the flow that calls for them, rather than as
/// code that nothing runs.
@MainActor
public final class WatchProgressStore {
    /// Duration changes smaller than this are the same content, not a re-read
    /// of a different file — Android's threshold, at sixty seconds.
    static let durationUpdateThresholdSeconds = 60.0

    private let defaults: UserDefaults
    private let clock: () -> Int64

    public init(
        defaults: UserDefaults = .standard,
        clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.defaults = defaults
        self.clock = clock
    }

    /// The canonical duration of the content, recorded whenever the player
    /// knows one. The player's own duration and the metadata's can disagree;
    /// this is what the reconciler would read first.
    public func setContentDuration(id: String, type: String, durationSeconds: Double, episodeId: String? = nil) {
        defaults.set(String(durationSeconds), forKey: contentDurationPrefKey(id: id, type: type, episodeId: episodeId))
    }

    public func getContentDurationSeconds(id: String, type: String, episodeId: String? = nil) -> Double? {
        let raw = defaults.string(forKey: contentDurationPrefKey(id: id, type: type, episodeId: episodeId))?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return raw.nilIfBlank.flatMap { Double($0) }
    }

    /// Re-stamps a position onto a longer cut of the same content: the
    /// percentage stays, the seconds move. A change inside the threshold is
    /// the same file with wobble, not a different one, and is skipped.
    public func updateProgressDuration(id: String, type: String, newDurationSeconds: Double, episodeId: String? = nil) {
        guard let existing = getWatchProgress(id: id, type: type, episodeId: episodeId) else { return }
        guard abs(existing.durationSeconds - newDurationSeconds) > Self.durationUpdateThresholdSeconds else { return }
        let percent = existing.progressPercentOrZero()
        let updated = WatchProgress(
            currentTimeSeconds: (percent / 100) * newDurationSeconds,
            durationSeconds: newDurationSeconds,
            lastUpdatedEpochMs: clock(),
            remoteImdbId: existing.remoteImdbId,
            addonId: existing.addonId
        )
        setWatchProgress(id: id, type: type, progress: updated, episodeId: episodeId)
    }

    /// The durability write. Every call is a meaningful write: the reporting
    /// cadence and the one-second floor are enforced upstream by the progress
    /// reporter and `resolveProgressWrite`, and the write always carries the
    /// report's own timestamp so a late call cannot resurrect an older state.
    public func setWatchProgress(id: String, type: String, progress: WatchProgress, episodeId: String? = nil) {
        let stamped = WatchProgress(
            currentTimeSeconds: progress.currentTimeSeconds,
            durationSeconds: progress.durationSeconds,
            lastUpdatedEpochMs: clock(),
            remoteImdbId: progress.remoteImdbId,
            addonId: progress.addonId
        )
        defaults.set(storedString(stamped), forKey: watchProgressPrefKey(id: id, type: type, episodeId: episodeId))
    }

    /// Tolerant read: a malformed blob is an absent entry, not a crash — the
    /// old parse the Android store upgraded with a warning.
    public func getWatchProgress(id: String, type: String, episodeId: String? = nil) -> WatchProgress? {
        guard let raw = defaults.string(forKey: watchProgressPrefKey(id: id, type: type, episodeId: episodeId)) else {
            return nil
        }
        guard let data = raw.data(using: .utf8),
              let entry = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            return nil
        }
        return WatchProgress(
            currentTimeSeconds: doubleValue(entry["currentTime"]) ?? 0,
            durationSeconds: doubleValue(entry["duration"]) ?? 0,
            lastUpdatedEpochMs: int64Value(entry["lastUpdated"]) ?? 0,
            remoteImdbId: stringValue(entry["remoteImdbId"]),
            addonId: stringValue(entry["addonId"])
        )
    }

    private func storedString(_ progress: WatchProgress) -> String {
        var entry: [String: Any] = [
            "currentTime": progress.currentTimeSeconds,
            "duration": progress.durationSeconds,
            "lastUpdated": progress.lastUpdatedEpochMs,
        ]
        if let remote = progress.remoteImdbId.nilIfBlank { entry["remoteImdbId"] = remote }
        if let addon = progress.addonId.nilIfBlank { entry["addonId"] = addon }
        guard let data = try? JSONSerialization.data(withJSONObject: entry) else { return "" }
        return String(data: data, encoding: .utf8) ?? ""
    }

    private func doubleValue(_ value: Any?) -> Double? {
        (value as? NSNumber)?.doubleValue
    }

    private func int64Value(_ value: Any?) -> Int64? {
        (value as? NSNumber)?.int64Value
    }

    private func stringValue(_ value: Any?) -> String? {
        switch value {
        case let text as String:
            return text.trimmingCharacters(in: .whitespacesAndNewlines).nilIfBlank
        case let number as NSNumber:
            // A numeric remote id keeps its stringification, the Android rule.
            return "\(number)"
        default:
            // JSON null — invisible as a String on the bridge — and anything
            // else are nothing.
            return nil
        }
    }
}

/// The key shapes, from Android's `WatchProgressKeys.kt`. They sit at file
/// level, not inside the store, for the same reason the Kotlin ones do: a test
/// has to name the exact key it is seeding or asserting, and a `private`
/// member of the store would make that impossible. `wpKeyString` is the
/// suffix the tombstone tables and the report the same way, so a writer and a
/// reader cannot drift apart.
internal let watchProgressKeyPrefix = "@watch_progress:"
internal let contentDurationKeyPrefix = "@content_duration:"

internal func wpKeyString(id: String, type: String, episodeId: String?) -> String {
    let base = "\(type):\(id)"
    guard let suffix = episodeId?.nilIfBlank else { return base }
    return "\(base):\(suffix)"
}

internal func watchProgressPrefKey(id: String, type: String, episodeId: String?) -> String {
    watchProgressKeyPrefix + wpKeyString(id: id, type: type, episodeId: episodeId)
}

internal func contentDurationPrefKey(id: String, type: String, episodeId: String?) -> String {
    contentDurationKeyPrefix + wpKeyString(id: id, type: type, episodeId: episodeId)
}

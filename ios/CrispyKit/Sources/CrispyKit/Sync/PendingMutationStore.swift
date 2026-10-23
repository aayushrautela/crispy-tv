import ContractRunner
import Foundation

/// The front half of the offline outbox, mirroring Android's
/// `PendingMutationStore` / `FileBackedPendingMutationStore`: one JSON file the
/// app relies on across process death, tolerant in both directions — a missing
/// or unreadable file reads as empty, and an entry recorded mid-send
/// (`inflight`) reads as `pending`, because the backend never saw it.
public protocol PendingMutationStoring: AnyObject {
    func loadAll() async -> [any UserMutation]
    /// Replaces the whole file; callers always write the full set.
    func saveAll(_ mutations: [any UserMutation]) async
}

/// The one file Android names `pending_mutations.json`, in Application Support.
public final class FileBackedPendingMutationStore: PendingMutationStoring {
    private let fileURL: URL
    /// File IO happens off the caller, on this serial queue, so two saves
    /// cannot interleave. Android serialises through a buffered sink on the IO
    /// dispatcher; a serial queue is the same guarantee with less machinery.
    private let queue: DispatchQueue

    public convenience init() {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        self.init(fileURL: support.appendingPathComponent("pending_mutations.json", isDirectory: false))
    }

    public init(fileURL: URL, queue: DispatchQueue = DispatchQueue(label: "crispy.pending-mutations", qos: .utility)) {
        self.fileURL = fileURL
        self.queue = queue
    }

    public func loadAll() async -> [any UserMutation] {
        let fileURL = fileURL
        return await withCheckedContinuation { continuation in
            queue.async {
                continuation.resume(returning: Self.readMutations(fileURL: fileURL))
            }
        }
    }

    public func saveAll(_ mutations: [any UserMutation]) async {
        let entries = mutations.map { encodePendingMutation($0) }
        let fileURL = fileURL
        await withCheckedContinuation { continuation in
            queue.async {
                do {
                    try FileManager.default.createDirectory(
                        at: fileURL.deletingLastPathComponent(),
                        withIntermediateDirectories: true
                    )
                    let data = try JSONSerialization.data(withJSONObject: entries)
                    // Atomic replace: a reader never sees half a file.
                    try data.write(to: fileURL, options: .atomic)
                } catch {
                    // The in-memory queue stays authoritative; the next save
                    // overwrites. Mirrors Android's swallowed write error.
                }
                continuation.resume()
            }
        }
    }

    private static func readMutations(fileURL: URL) -> [any UserMutation] {
        guard let data = try? Data(contentsOf: fileURL) else { return [] }
        guard let entries = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] else {
            return []
        }
        return entries.compactMap { decodePendingMutation($0) }
    }
}

/// One stored entry. The keys are Android's decode table, so the shape of the
/// file stays a mirror even though the two platforms never read each other's
/// files: tolerant in exactly the same places.
internal func encodePendingMutation(_ mutation: any UserMutation) -> [String: Any] {
    var entry: [String: Any] = [
        "type": mutation.kind.rawValue,
        "id": mutation.id,
        "titleItemId": mutation.titleItemId,
        "entityId": mutation.entityId,
        "createdAtMs": mutation.createdAtMs,
        "attempt": mutation.attempt,
        "nextAttemptAtMs": mutation.nextAttemptAtMs,
    ]
    switch mutation.status {
    case .pending:
        entry["status"] = "pending"
    case .inflight:
        entry["status"] = "inflight"
    case let .failed(reason, retryable):
        entry["status"] = "failed"
        entry["statusReason"] = reason
        entry["retryable"] = retryable
    case let .conflict(serverValue):
        entry["status"] = "conflict"
        entry["statusServerValue"] = serverValue
    }
    switch mutation {
    case let m as WatchlistMutation:
        entry["desired"] = m.desired
    case let m as TitleWatchedMutation:
        entry["desired"] = m.desired
        entry["contentType"] = m.contentType.rawValue
    case let m as RatingMutation:
        // `nil` is stored as an explicit null, because "clear" is not "absent".
        entry["desired"] = m.desired ?? NSNull()
    case let m as EpisodeWatchedMutation:
        entry["itemId"] = m.itemId
        entry["season"] = m.season
        if let episode = m.episode { entry["episode"] = episode }
        entry["videoId"] = m.videoId
        entry["desired"] = m.desired
    case let m as SeasonWatchedMutation:
        entry["seasonItemId"] = m.seasonItemId
        entry["seasonNumber"] = m.seasonNumber
        entry["desired"] = m.desired
    default:
        break
    }
    return entry
}

/// Inverse of `encodePendingMutation`, with Android's tolerances: an unknown
/// kind or a missing required field drops the entry (`compactMap`), an unknown
/// or `inflight` status reads as `pending`, a unlabelled failed entry is
/// retryable, and a content type the table does not name reads as `.movie`.
internal func decodePendingMutation(_ entry: [String: Any]) -> (any UserMutation)? {
    guard
        let type = entry["type"] as? String,
        let kind = MutationKind(rawValue: type),
        let id = entry["id"] as? String,
        let titleItemId = entry["titleItemId"] as? String,
        let entityId = entry["entityId"] as? String,
        let createdAtMs = int64Value(entry["createdAtMs"]),
        let attempt = intValue(entry["attempt"]),
        let nextAttemptAtMs = int64Value(entry["nextAttemptAtMs"])
    else { return nil }
    let status = decodeStatus(entry)

    switch kind {
    case .watchlist:
        guard let desired = entry["desired"] as? Bool else { return nil }
        return WatchlistMutation(
            id: id, titleItemId: titleItemId, entityId: entityId, createdAtMs: createdAtMs,
            attempt: attempt, status: status, nextAttemptAtMs: nextAttemptAtMs, desired: desired
        )
    case .titleWatched:
        guard let desired = entry["desired"] as? Bool else { return nil }
        let contentType = (entry["contentType"] as? String).flatMap(MediaContentType.init(rawValue:)) ?? .movie
        return TitleWatchedMutation(
            id: id, titleItemId: titleItemId, entityId: entityId, createdAtMs: createdAtMs,
            attempt: attempt, status: status, nextAttemptAtMs: nextAttemptAtMs,
            contentType: contentType, desired: desired
        )
    case .rating:
        // The three encodings of "no vote": an absent key, an explicit null,
        // and `true`/`false`.
        let raw = entry["desired"]
        let desired: Bool?
        if raw == nil || raw is NSNull {
            desired = nil
        } else {
            desired = raw as? Bool
        }
        guard desired != nil || raw is NSNull else { return nil }
        return RatingMutation(
            id: id, titleItemId: titleItemId, entityId: entityId, createdAtMs: createdAtMs,
            attempt: attempt, status: status, nextAttemptAtMs: nextAttemptAtMs, desired: desired
        )
    case .episodeWatched:
        guard
            let itemId = entry["itemId"] as? String,
            let season = intValue(entry["season"]),
            let videoId = entry["videoId"] as? String,
            let desired = entry["desired"] as? Bool
        else { return nil }
        // A stored 0 is a real episode number; only an absent key means none.
        let episode = intValue(entry["episode"])
        return EpisodeWatchedMutation(
            id: id, titleItemId: titleItemId, entityId: entityId, createdAtMs: createdAtMs,
            attempt: attempt, status: status, nextAttemptAtMs: nextAttemptAtMs,
            itemId: itemId, season: season, episode: episode, videoId: videoId, desired: desired
        )
    case .seasonWatched:
        guard
            let seasonItemId = entry["seasonItemId"] as? String,
            let seasonNumber = intValue(entry["seasonNumber"]),
            let desired = entry["desired"] as? Bool
        else { return nil }
        return SeasonWatchedMutation(
            id: id, titleItemId: titleItemId, entityId: entityId, createdAtMs: createdAtMs,
            attempt: attempt, status: status, nextAttemptAtMs: nextAttemptAtMs,
            seasonItemId: seasonItemId, seasonNumber: seasonNumber, desired: desired
        )
    }
}

private func decodeStatus(_ entry: [String: Any]) -> MutationStatus {
    switch (entry["status"] as? String).nilIfBlank {
    case "failed":
        // An unlabelled failure is retryable: the writer died before it could
        // say otherwise.
        return .failed(
            reason: entry["statusReason"] as? String ?? "",
            retryable: entry["retryable"] as? Bool ?? true
        )
    case "conflict":
        return .conflict(serverValue: entry["statusServerValue"] as? String)
    default:
        // `pending`, and `inflight` — which the backend never saw either.
        return .pending
    }
}

private func int64Value(_ value: Any?) -> Int64? {
    (value as? NSNumber)?.int64Value
}

private func intValue(_ value: Any?) -> Int? {
    (value as? NSNumber)?.intValue
}

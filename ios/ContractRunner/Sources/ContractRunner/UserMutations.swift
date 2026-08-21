import Foundation

/// Pure, deterministic model for optimistic user-mutation state.
/// Mirrors `android/core-domain/.../optimistic/UserMutations.kt` 1:1.

public enum MutationKind: String, Codable, Equatable {
    case watchlist
    case titleWatched = "title_watched"
    case episodeWatched = "episode_watched"
    case seasonWatched = "season_watched"
    case rating
}

/// Pure copy of the content-type taxonomy the watch-history backend needs.
/// Mirrors `android/core-domain/.../optimistic/UserMutations.kt`.
public enum MediaContentType: String, Codable, Equatable {
    case movie
    case series
    case anime
}

public enum MutationStatus: Equatable {
    case pending
    case inflight
    case failed(reason: String, retryable: Bool)
    case conflict(serverValue: String?)
}

public protocol UserMutation: Equatable {
    var id: String { get }
    var titleItemId: String { get }
    var entityId: String { get }
    var kind: MutationKind { get }
    var createdAtMs: Int64 { get }
    var attempt: Int { get }
    var status: MutationStatus { get }
    var nextAttemptAtMs: Int64 { get }
}

public struct WatchlistMutation: UserMutation {
    public let id: String
    public let titleItemId: String
    public let entityId: String
    public let createdAtMs: Int64
    public let attempt: Int
    public let status: MutationStatus
    public let nextAttemptAtMs: Int64
    public let desired: Bool
    public var kind: MutationKind { .watchlist }
    public init(id: String, titleItemId: String, entityId: String, createdAtMs: Int64, attempt: Int, status: MutationStatus, nextAttemptAtMs: Int64, desired: Bool) {
        self.id = id
        self.titleItemId = titleItemId
        self.entityId = entityId
        self.createdAtMs = createdAtMs
        self.attempt = attempt
        self.status = status
        self.nextAttemptAtMs = nextAttemptAtMs
        self.desired = desired
    }
}

public struct TitleWatchedMutation: UserMutation {
    public let id: String
    public let titleItemId: String
    public let entityId: String
    public let createdAtMs: Int64
    public let attempt: Int
    public let status: MutationStatus
    public let nextAttemptAtMs: Int64
    public let contentType: MediaContentType
    public let desired: Bool
    public var kind: MutationKind { .titleWatched }
    public init(id: String, titleItemId: String, entityId: String, createdAtMs: Int64, attempt: Int, status: MutationStatus, nextAttemptAtMs: Int64, contentType: MediaContentType = .movie, desired: Bool) {
        self.id = id
        self.titleItemId = titleItemId
        self.entityId = entityId
        self.createdAtMs = createdAtMs
        self.attempt = attempt
        self.status = status
        self.nextAttemptAtMs = nextAttemptAtMs
        self.contentType = contentType
        self.desired = desired
    }
}


public struct RatingMutation: UserMutation {
    public let id: String
    public let titleItemId: String
    public let entityId: String
    public let createdAtMs: Int64
    public let attempt: Int
    public let status: MutationStatus
    public let nextAttemptAtMs: Int64
    public let desired: Int?
    public var kind: MutationKind { .rating }
    public init(id: String, titleItemId: String, entityId: String, createdAtMs: Int64, attempt: Int, status: MutationStatus, nextAttemptAtMs: Int64, desired: Int?) {
        self.id = id
        self.titleItemId = titleItemId
        self.entityId = entityId
        self.createdAtMs = createdAtMs
        self.attempt = attempt
        self.status = status
        self.nextAttemptAtMs = nextAttemptAtMs
        self.desired = desired
    }
}

public struct EpisodeWatchedMutation: UserMutation {
    public let id: String
    public let titleItemId: String
    public let entityId: String
    public let createdAtMs: Int64
    public let attempt: Int
    public let status: MutationStatus
    public let nextAttemptAtMs: Int64
    public let itemId: String
    public let season: Int
    public let episode: Int?
    public let videoId: String
    public let desired: Bool
    public var kind: MutationKind { .episodeWatched }
    public init(id: String, titleItemId: String, entityId: String, createdAtMs: Int64, attempt: Int, status: MutationStatus, nextAttemptAtMs: Int64, itemId: String, season: Int, episode: Int?, videoId: String, desired: Bool) {
        self.id = id
        self.titleItemId = titleItemId
        self.entityId = entityId
        self.createdAtMs = createdAtMs
        self.attempt = attempt
        self.status = status
        self.nextAttemptAtMs = nextAttemptAtMs
        self.itemId = itemId
        self.season = season
        self.episode = episode
        self.videoId = videoId
        self.desired = desired
    }
}

public struct SeasonWatchedMutation: UserMutation {
    public let id: String
    public let titleItemId: String
    public let entityId: String
    public let createdAtMs: Int64
    public let attempt: Int
    public let status: MutationStatus
    public let nextAttemptAtMs: Int64
    public let seasonItemId: String
    public let seasonNumber: Int
    public let desired: Bool
    public var kind: MutationKind { .seasonWatched }
    public init(id: String, titleItemId: String, entityId: String, createdAtMs: Int64, attempt: Int, status: MutationStatus, nextAttemptAtMs: Int64, seasonItemId: String, seasonNumber: Int, desired: Bool) {
        self.id = id
        self.titleItemId = titleItemId
        self.entityId = entityId
        self.createdAtMs = createdAtMs
        self.attempt = attempt
        self.status = status
        self.nextAttemptAtMs = nextAttemptAtMs
        self.seasonItemId = seasonItemId
        self.seasonNumber = seasonNumber
        self.desired = desired
    }
}

public struct UserStateSnapshot {
    public let isInWatchlist: Bool
    public let isWatched: Bool
    public let isRated: Bool
    public let userRating: Int?
    public let episodeWatched: [String: Bool]
    public let seasonWatched: [Int: Bool]
    public init(isInWatchlist: Bool = false, isWatched: Bool = false, isRated: Bool = false, userRating: Int? = nil, episodeWatched: [String: Bool] = [:], seasonWatched: [Int: Bool] = [:]) {
        self.isInWatchlist = isInWatchlist
        self.isWatched = isWatched
        self.isRated = isRated
        self.userRating = userRating
        self.episodeWatched = episodeWatched
        self.seasonWatched = seasonWatched
    }
}

public enum FieldSync: String, Equatable {
    case idle
    case syncing
    case error
}

public struct MutationSyncView: Equatable {
    public let status: FieldSync
    public let errorMessage: String?
    public init(status: FieldSync, errorMessage: String? = nil) {
        self.status = status
        self.errorMessage = errorMessage
    }
}

public struct DerivedUserState {
    public let watchlist: (Bool, MutationSyncView)
    public let titleWatched: (Bool, MutationSyncView)
    public let rating: (Int?, MutationSyncView)
    public let episodeWatched: [String: (Bool, MutationSyncView)]
    public let seasonWatched: [Int: (Bool, MutationSyncView)]
}

public struct RetryPolicy {
    public let baseDelayMs: Int64
    public let maxDelayMs: Int64
    public let maxAttempts: Int
    public init(baseDelayMs: Int64 = 1000, maxDelayMs: Int64 = 60000, maxAttempts: Int = 8) {
        self.baseDelayMs = baseDelayMs
        self.maxDelayMs = maxDelayMs
        self.maxAttempts = maxAttempts
    }
}

public struct OutboxAction: Equatable {
    public let mutationId: String
    public let kind: MutationKind
    public init(mutationId: String, kind: MutationKind) {
        self.mutationId = mutationId
        self.kind = kind
    }
}

public func nextBackoffDelayMs(attempt: Int, policy: RetryPolicy) -> Int64 {
    if attempt <= 0 { return policy.baseDelayMs }
    let exponent = min(attempt - 1, 30)
    let shifted: Int64
    if exponent >= 63 {
        shifted = Int64.max
    } else {
        shifted = policy.baseDelayMs * (1 << exponent)
    }
    return min(max(shifted, 0), policy.maxDelayMs)
}

public func coalesceMutations(_ mutations: [any UserMutation]) -> [any UserMutation] {
    if mutations.isEmpty { return [] }
    var byKey: [String: any UserMutation] = [:]
    for mutation in mutations {
        let key = mutation.kind.rawValue + "#" + mutation.entityId
        if let existing = byKey[key] {
            if mutation.createdAtMs >= existing.createdAtMs {
                byKey[key] = mutation
            }
        } else {
            byKey[key] = mutation
        }
    }
    return byKey.values.sorted { ($0.createdAtMs, $0.id) < ($1.createdAtMs, $1.id) }
}

private func latestOf(_ group: [any UserMutation]) -> any UserMutation? {
    group.max { ($0.createdAtMs, $0.id) < ($1.createdAtMs, $1.id) }
}

public func deriveUserState(snapshot: UserStateSnapshot, mutations: [any UserMutation]) -> DerivedUserState {
    let grouped = Dictionary(grouping: mutations, by: { $0.kind })

    func active(_ kind: MutationKind) -> any UserMutation? {
        latestOf(grouped[kind] ?? [])
    }

    func reduce<T>(server: T, mutation: (any UserMutation)?, desiredOf: (any UserMutation) -> T) -> (T, MutationSyncView) {
        guard let mutation = mutation else {
            return (server, MutationSyncView(status: .idle))
        }
        switch mutation.status {
        case .pending, .inflight:
            return (desiredOf(mutation), MutationSyncView(status: .syncing))
        case let .failed(reason, _):
            let message = reason.isEmpty ? "Update failed." : reason
            return (server, MutationSyncView(status: .error, errorMessage: message))
        case let .conflict(serverValue):
            let message = serverValue.map { "Server has a different value: \($0)" } ?? "Server rejected the change."
            return (server, MutationSyncView(status: .error, errorMessage: message))
        }
    }

    let watchlist = reduce(server: snapshot.isInWatchlist, mutation: active(.watchlist)) { ($0 as! WatchlistMutation).desired }
    let titleWatched = reduce(server: snapshot.isWatched, mutation: active(.titleWatched)) { ($0 as! TitleWatchedMutation).desired }
    let rating = reduce(server: snapshot.userRating, mutation: active(.rating)) { ($0 as! RatingMutation).desired }

    var episodeWatched = snapshot.episodeWatched
    var episodeSync: [String: MutationSyncView] = [:]
    for mutation in grouped[.episodeWatched] ?? [] {
        let m = mutation as! EpisodeWatchedMutation
        let derived = reduce(server: episodeWatched[m.videoId] ?? false, mutation: m) { ($0 as! EpisodeWatchedMutation).desired }
        episodeWatched[m.videoId] = derived.0
        episodeSync[m.videoId] = derived.1
    }

    var seasonWatched = snapshot.seasonWatched
    var seasonSync: [Int: MutationSyncView] = [:]
    for mutation in grouped[.seasonWatched] ?? [] {
        let m = mutation as! SeasonWatchedMutation
        let derived = reduce(server: seasonWatched[m.seasonNumber] ?? false, mutation: m) { ($0 as! SeasonWatchedMutation).desired }
        seasonWatched[m.seasonNumber] = derived.0
        seasonSync[m.seasonNumber] = derived.1
    }

    let episodeDerived = Dictionary(
        uniqueKeysWithValues: episodeWatched.map { (key, value) in
            (key, (value, episodeSync[key] ?? MutationSyncView(status: .idle)))
        }
    )
    let seasonDerived = Dictionary(
        uniqueKeysWithValues: seasonWatched.map { (key, value) in
            (key, (value, seasonSync[key] ?? MutationSyncView(status: .idle)))
        }
    )

    return DerivedUserState(
        watchlist: watchlist,
        titleWatched: titleWatched,
        rating: rating,
        episodeWatched: episodeDerived,
        seasonWatched: seasonDerived
    )
}

public func planOutbox(mutations: [any UserMutation], nowMs: Int64) -> [OutboxAction] {
    mutations
        .filter { mutation in
            if case .pending = mutation.status { return mutation.nextAttemptAtMs <= nowMs }
            return false
        }
        .sorted { ($0.createdAtMs, $0.id) < ($1.createdAtMs, $1.id) }
        .map { OutboxAction(mutationId: $0.id, kind: $0.kind) }
}

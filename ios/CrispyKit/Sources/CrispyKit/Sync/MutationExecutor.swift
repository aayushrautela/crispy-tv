import ContractRunner
import Foundation

/// The outcome of one executor run, the twin of `MutationResult` on Android:
/// success, an optional server conflict, and an optional reason for failures.
public struct MutationResult: Equatable {
    public let success: Bool
    public let conflict: Bool
    public let serverValue: String?
    public let reason: String?

    public init(success: Bool, conflict: Bool = false, serverValue: String? = nil, reason: String? = nil) {
        self.success = success
        self.conflict = conflict
        self.serverValue = serverValue
        self.reason = reason
    }
}

/// Executes one mutation on the outbox's behalf.
@MainActor
public protocol MutationExecuting {
    func execute(_ mutation: any UserMutation) async throws -> MutationResult
}

/// Maps the outbox's mutation kinds onto the backend's watch actions, the twin
/// of Android's `UserMediaMutationExecutor`. Conflicts are never detected here:
/// the Android executor only reports `accepted` and a reason, and so does this
/// one — the `conflict` status stays an escape hatch for a smarter executor.
@MainActor
public struct MediaMutationExecutor: MutationExecuting {
    private let environment: AppEnvironment

    public init(environment: AppEnvironment) {
        self.environment = environment
    }

    public func execute(_ mutation: any UserMutation) async throws -> MutationResult {
        guard let context = await environment.backendContext() else {
            return MutationResult(success: false, reason: "Select a profile to update your library.")
        }
        switch mutation.kind {
        case .watchlist:
            let watchlist = mutation as! WatchlistMutation
            return try await watchAction {
                watchlist.desired
                    ? try await environment.backend.putWatchlist(accessToken: context.accessToken, profileId: context.profileId, itemId: watchlist.entityId)
                    : try await environment.backend.deleteWatchlist(accessToken: context.accessToken, profileId: context.profileId, itemId: watchlist.entityId)
            }
        case .rating:
            let rating = mutation as! RatingMutation
            if let desired = rating.desired {
                return try await watchAction {
                    try await environment.backend.setLiked(accessToken: context.accessToken, profileId: context.profileId, itemId: rating.entityId, liked: desired)
                }
            }
            return try await watchAction {
                try await environment.backend.deleteRating(accessToken: context.accessToken, profileId: context.profileId, itemId: rating.entityId)
            }
        case .titleWatched:
            let watched = mutation as! TitleWatchedMutation
            return try await watchAction {
                watched.desired
                    ? try await environment.backend.markWatched(accessToken: context.accessToken, profileId: context.profileId, itemId: watched.titleItemId)
                    : try await environment.backend.unmarkWatched(accessToken: context.accessToken, profileId: context.profileId, itemId: watched.titleItemId)
            }
        case .episodeWatched, .seasonWatched:
            // The kinds exist for contract parity, but nothing enqueues them
            // from the iOS app yet — no screen offers a season or episode
            // toggle — and the backend client has no episode surface for it.
            return MutationResult(success: false, reason: "Episode and season toggles have no iOS surface yet.")
        }
    }

    private func watchAction(_ action: () async throws -> WatchActionResponse) async throws -> MutationResult {
        do {
            let response = try await action()
            if response.accepted {
                return MutationResult(success: true)
            }
            return MutationResult(success: false, reason: response.reason.nilIfBlank)
        } catch let error as CancellationError {
            // Never swallow cancellation: the outbox treats it as "stop".
            throw error
        } catch {
            // A thrown call is a network problem, not a decision by the
            // backend, so no reason is reported and the outbox retries.
            return MutationResult(success: false, reason: nil)
        }
    }
}

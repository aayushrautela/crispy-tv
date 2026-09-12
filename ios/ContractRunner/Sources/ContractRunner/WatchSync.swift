import Foundation

public enum WatchSyncConnection: Equatable {
    case disconnected
    case connecting
    case connected
}

public struct WatchSyncState: Equatable {
    public let profileId: String
    public let connection: WatchSyncConnection
    public let isSurfaceVisible: Bool

    public init(profileId: String, connection: WatchSyncConnection, isSurfaceVisible: Bool) {
        self.profileId = profileId
        self.connection = connection
        self.isSurfaceVisible = isSurfaceVisible
    }
}

/// The surface an invalidation targets. Sent by the server on the `watch_changed`
/// SSE event so the client can route each invalidation to the right refetch.
public enum WatchSyncKind: Equatable {
    case continueWatching
    case history
    case watchlist
    case ratings
    case home
    case unknown

    /// A legacy server omits `kind`; treat it as `continueWatching`.
    static func fromRaw(_ raw: String?) -> WatchSyncKind {
        switch raw?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "continue_watching": return .continueWatching
        case "history": return .history
        case "watchlist": return .watchlist
        case "ratings": return .ratings
        case "home": return .home
        case nil, "": return .continueWatching
        default: return .unknown
        }
    }
}

public enum WatchSyncEvent: Equatable {
    case surfaceBecameVisible
    case surfaceHidden
    case connectionOpened
    case connectionClosed
    case maxDurationElapsed
    case invalidationReceived(profileId: String, kind: WatchSyncKind, atMs: Int64)
}

public enum WatchSyncEffect: Equatable {
    case openConnection
    case closeConnection
    case refetchContinueWatching
    case refetchHistory
    case refetchWatchlist
    case refetchRatings
    case refetchHome

    public var contractValue: String {
        switch self {
        case .openConnection: return "open_connection"
        case .closeConnection: return "close_connection"
        case .refetchContinueWatching: return "refetch_continue_watching"
        case .refetchHistory: return "refetch_history"
        case .refetchWatchlist: return "refetch_watchlist"
        case .refetchRatings: return "refetch_ratings"
        case .refetchHome: return "refetch_home"
        }
    }
}

public struct WatchSyncResult: Equatable {
    public let state: WatchSyncState
    public let effects: [WatchSyncEffect]
}

public func createWatchSyncState(profileId: String) -> WatchSyncState {
    WatchSyncState(profileId: profileId, connection: .disconnected, isSurfaceVisible: false)
}

private func effects(for kind: WatchSyncKind) -> [WatchSyncEffect] {
    switch kind {
    case .continueWatching: return [.refetchContinueWatching]
    case .history: return [.refetchContinueWatching, .refetchHistory]
    case .watchlist: return [.refetchWatchlist]
    case .ratings: return [.refetchRatings]
    case .home: return [.refetchHome]
    case .unknown: return []
    }
}

public func reduceWatchSync(state: WatchSyncState, event: WatchSyncEvent) -> WatchSyncResult {
    switch event {
    case .surfaceBecameVisible:
        if state.connection == .disconnected {
            return WatchSyncResult(
                state: WatchSyncState(profileId: state.profileId, connection: .connecting, isSurfaceVisible: true),
                effects: [.openConnection]
            )
        }
        return WatchSyncResult(
            state: WatchSyncState(profileId: state.profileId, connection: state.connection, isSurfaceVisible: true),
            effects: []
        )

    case .surfaceHidden:
        if state.connection != .disconnected {
            return WatchSyncResult(
                state: WatchSyncState(profileId: state.profileId, connection: .disconnected, isSurfaceVisible: false),
                effects: [.closeConnection]
            )
        }
        return WatchSyncResult(
            state: WatchSyncState(profileId: state.profileId, connection: .disconnected, isSurfaceVisible: false),
            effects: []
        )

    case .connectionOpened:
        if state.isSurfaceVisible {
            return WatchSyncResult(
                state: WatchSyncState(profileId: state.profileId, connection: .connected, isSurfaceVisible: state.isSurfaceVisible),
                effects: [.refetchContinueWatching, .refetchHome]
            )
        }
        return WatchSyncResult(
            state: WatchSyncState(profileId: state.profileId, connection: .disconnected, isSurfaceVisible: state.isSurfaceVisible),
            effects: [.closeConnection]
        )

    case .connectionClosed:
        return WatchSyncResult(
            state: WatchSyncState(profileId: state.profileId, connection: .disconnected, isSurfaceVisible: state.isSurfaceVisible),
            effects: []
        )

    case let .invalidationReceived(profileId, kind, _):
        if state.connection == .connected && profileId == state.profileId {
            return WatchSyncResult(state: state, effects: effects(for: kind))
        }
        return WatchSyncResult(state: state, effects: [])

    case .maxDurationElapsed:
        if state.connection != .disconnected {
            if state.isSurfaceVisible {
                return WatchSyncResult(
                    state: WatchSyncState(profileId: state.profileId, connection: .connecting, isSurfaceVisible: state.isSurfaceVisible),
                    effects: [.closeConnection, .openConnection]
                )
            }
            return WatchSyncResult(
                state: WatchSyncState(profileId: state.profileId, connection: .disconnected, isSurfaceVisible: state.isSurfaceVisible),
                effects: [.closeConnection]
            )
        }
        return WatchSyncResult(state: state, effects: [])
    }
}

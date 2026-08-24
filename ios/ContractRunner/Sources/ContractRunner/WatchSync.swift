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

public enum WatchSyncEvent: Equatable {
    case surfaceBecameVisible
    case surfaceHidden
    case connectionOpened
    case connectionClosed
    case maxDurationElapsed
    case invalidationReceived(profileId: String, atMs: Int64)
}

public enum WatchSyncEffect: Equatable {
    case openConnection
    case closeConnection
    case refetchContinueWatching

    public var contractValue: String {
        switch self {
        case .openConnection:
            return "open_connection"
        case .closeConnection:
            return "close_connection"
        case .refetchContinueWatching:
            return "refetch_continue_watching"
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
                effects: [.refetchContinueWatching]
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

    case let .invalidationReceived(profileId, _):
        if state.connection == .connected && profileId == state.profileId {
            return WatchSyncResult(state: state, effects: [.refetchContinueWatching])
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

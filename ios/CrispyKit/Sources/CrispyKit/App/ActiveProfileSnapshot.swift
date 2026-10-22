import Foundation

/// Read-only snapshot of the active profile for chrome that is not a screen
/// (tab avatar buttons and their menu), mirroring Android's `ActiveProfileInfo`
/// from `ProfileMenuRoute.loadActiveProfile`.
public struct ActiveProfileSnapshot: Equatable, Sendable {
    public let id: String
    public let name: String?
    public let avatarUrl: String?

    public init(id: String, name: String?, avatarUrl: String?) {
        self.id = id
        self.name = name
        self.avatarUrl = avatarUrl
    }

    /// The stored id wins when it still names a profile; otherwise the first
    /// profile is the active one. No profiles means no snapshot.
    public static func resolve(profiles: [BackendProfile], activeId: String?) -> ActiveProfileSnapshot? {
        guard let profile = profiles.first(where: { $0.id == activeId }) ?? profiles.first else {
            return nil
        }
        return ActiveProfileSnapshot(
            id: profile.id,
            name: profile.name.nilIfBlank,
            avatarUrl: profile.avatarUrl?.nilIfBlank
        )
    }
}

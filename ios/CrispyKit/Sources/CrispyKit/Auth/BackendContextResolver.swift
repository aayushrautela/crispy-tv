import Foundation

public struct BackendContext: Equatable {
public let accessToken: String
public let profileId: String
}

/// Resolves the (accessToken, activeProfileId) pair every backend call needs.
/// Mirrors the Android `BackendContextResolver`: onboarding is complete once a
/// context resolves.
@MainActor
public final class BackendContextResolver {
    private let supabase: SupabaseAccountClient
    private let profileStore: ActiveProfileStore

public init(supabase: SupabaseAccountClient, profileStore: ActiveProfileStore) {
        self.supabase = supabase
        self.profileStore = profileStore
    }

public func resolve() async -> BackendContext? {
        guard let session = await supabase.ensureValidSession() else { return nil }
        guard let profileId = profileStore.activeProfileId(userId: session.userId) else { return nil }
        return BackendContext(accessToken: session.accessToken, profileId: profileId)
    }

public func clear() {
        // Active-profile wipe is handled by ActiveProfileStore.clear at sign-out;
        // nothing cached here yet.
    }
}

import Foundation
import Observation

/// Single source of truth for app-level bootstrap state, mirroring the
/// Android `AppBootstrapViewModel`.
@MainActor
@Observable
public final class BootstrapViewModel {
    public enum State {
        case loading
        case needsAuth
        case needsProfileSelection
        case ready
    }

    public private(set) var state: State = .loading

    private let supabase: SupabaseAccountClient
    private let profileStore: ActiveProfileStore

public init(supabase: SupabaseAccountClient, profileStore: ActiveProfileStore) {
        self.supabase = supabase
        self.profileStore = profileStore
    }

    /// Re-run bootstrap and flip state accordingly. Safe to call from auth /
    /// profile screens and after sign-out.
public func refresh() async {
        state = .loading
        guard let session = await supabase.ensureValidSession() else {
            state = .needsAuth
            return
        }
        let onboardingComplete = profileStore.activeProfileId(userId: session.userId) != nil
        state = onboardingComplete ? .ready : .needsProfileSelection
    }

    /// Called after a completed sign-out so the root gate flips back to auth.
public func markSignedOut() {
        state = .needsAuth
    }
}

/// Composition root for the iOS app (architecture.md: one AppContainer).
@MainActor
@Observable
public final class AppEnvironment {
public let config: AppConfig
public let httpClient: CrispyHttpClient
public let backend: CrispyBackendClient
public let supabase: SupabaseAccountClient
public let tokenStore: SessionStoring
public let profileStore: ActiveProfileStore
public let contextResolver: BackendContextResolver
public let bootstrap: BootstrapViewModel

public init(config: AppConfig = .load()) {
        self.config = config
        self.httpClient = CrispyHttpClient()
        self.backend = CrispyBackendClient(httpClient: httpClient, backendURL: config.backendURL)
        self.tokenStore = KeychainSessionStore()
        self.profileStore = ActiveProfileStore()
        self.supabase = SupabaseAccountClient(
            httpClient: httpClient,
            supabaseURL: config.supabaseURL,
            publishableKey: config.supabasePublishableKey,
            tokenStore: tokenStore
        )
        self.contextResolver = BackendContextResolver(supabase: supabase, profileStore: profileStore)
        self.bootstrap = BootstrapViewModel(supabase: supabase, profileStore: profileStore)
    }

    /// Resolves the current backend context, refreshing the session if needed.
public func backendContext() async -> BackendContext? {
        await contextResolver.resolve()
    }

    /// One locked sequence: revoke server-side first, then always wipe local.
public func signOut() async {
        let userId = supabase.currentSession()?.userId
        await supabase.signOut()
        tokenStore.clear()
        profileStore.clear(userId: userId)
        contextResolver.clear()
    }
}

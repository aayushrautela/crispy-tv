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
/// Resolves the streams the account's addons offer for a title.
public let streamsClient: AddonStreamsClient
/// The playback preferences the player and the stream picker honour.
public let playbackSettings: PlaybackSettingsStore
/// The local half of progress persistence: every playback report is written
/// here before it goes to the backend, and the resume lookup reads it first.
public let watchProgressStore: WatchProgressStore
/// The offline outbox for user mutations (watchlist, watched, ratings),
/// started in the composition root like Android's. Implicitly unwrapped
/// because its executor needs the environment itself: the property is nil
/// only until init's second phase assigns it the finished instance, one
/// line later, on the main actor.
public private(set) var userMutationOutbox: UserMutationOutbox!
/// The active profile the tab chrome shows. Resolved by
/// `refreshActiveProfile()` (or adopted from a list a screen just fetched).
public private(set) var activeProfile: ActiveProfileSnapshot?

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
        self.streamsClient = AddonStreamsClient(http: httpClient)
        self.playbackSettings = PlaybackSettingsStore()
        self.watchProgressStore = WatchProgressStore()
        // Every stored property is initialized from here, so `self` can be
        // passed as an argument — which is what the executor needs.
        userMutationOutbox = UserMutationOutbox(
            store: FileBackedPendingMutationStore(),
            executor: MediaMutationExecutor(environment: self)
        )
        userMutationOutbox.startOnce()
    }

    /// Resolves the current backend context, refreshing the session if needed.
public func backendContext() async -> BackendContext? {
        await contextResolver.resolve()
    }

    /// Re-resolves the active profile from the backend and publishes it for the
    /// tab chrome, mirroring Android's `loadActiveProfile`. A failed or empty
    /// fetch keeps the last snapshot rather than clearing the avatar.
public func refreshActiveProfile() async {
        guard let context = await backendContext() else { return }
        guard let profiles = try? await backend.listProfiles(accessToken: context.accessToken),
              !profiles.isEmpty else { return }
        activeProfile = ActiveProfileSnapshot.resolve(
            profiles: profiles,
            activeId: profileStore.activeProfileId(userId: supabase.currentSession()?.userId)
        )
    }

    /// Adopts the snapshot from a profile list the caller already fetched (the
    /// profile menu, profile management), so chrome updates without a second
    /// round trip. An empty list is ignored for the same reason as above.
public func adoptActiveProfile(profiles: [BackendProfile]) {
        guard !profiles.isEmpty else { return }
        activeProfile = ActiveProfileSnapshot.resolve(
            profiles: profiles,
            activeId: profileStore.activeProfileId(userId: supabase.currentSession()?.userId)
        )
    }

    /// One locked sequence: revoke server-side first, then always wipe local.
public func signOut() async {
        let userId = supabase.currentSession()?.userId
        await supabase.signOut()
        tokenStore.clear()
        profileStore.clear(userId: userId)
        contextResolver.clear()
        activeProfile = nil
    }
}

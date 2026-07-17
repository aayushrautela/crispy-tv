import Foundation

public struct ProfileSettings: Equatable {
    public var displayName: String?
    public var avatarUrl: String?
    public var syncProvider: String?
    public var onboardingStep: String?
    public var onboardingCompletedAtMs: Int64?

    public init(
        displayName: String? = nil,
        avatarUrl: String? = nil,
        syncProvider: String? = nil,
        onboardingStep: String? = nil,
        onboardingCompletedAtMs: Int64? = nil
    ) {
        self.displayName = displayName
        self.avatarUrl = avatarUrl
        self.syncProvider = syncProvider
        self.onboardingStep = onboardingStep
        self.onboardingCompletedAtMs = onboardingCompletedAtMs
    }
}

public func mergeProfileSettings(_ local: ProfileSettings, _ server: ProfileSettings) -> ProfileSettings {
    return ProfileSettings(
        displayName: server.displayName ?? local.displayName,
        avatarUrl: server.avatarUrl ?? local.avatarUrl,
        syncProvider: server.syncProvider ?? local.syncProvider,
        onboardingStep: server.onboardingStep ?? local.onboardingStep,
        onboardingCompletedAtMs: server.onboardingCompletedAtMs ?? local.onboardingCompletedAtMs
    )
}

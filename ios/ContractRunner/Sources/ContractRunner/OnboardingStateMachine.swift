import Foundation

public enum OnboardingStep: String {
    case service = "SERVICE"
    case complete = "COMPLETE"
}

public struct OnboardingState {
    public let currentStep: OnboardingStep
    public let connectedService: String?

    public init(currentStep: OnboardingStep, connectedService: String?) {
        self.currentStep = currentStep
        self.connectedService = connectedService
    }
}

public struct OnboardingTransition {
    public let nextStep: OnboardingStep
    public let isComplete: Bool

    public init(nextStep: OnboardingStep, isComplete: Bool) {
        self.nextStep = nextStep
        self.isComplete = isComplete
    }
}

public func advanceOnboarding(_ state: OnboardingState) -> OnboardingTransition {
    if state.connectedService != nil {
        return OnboardingTransition(nextStep: .complete, isComplete: true)
    }
    return OnboardingTransition(nextStep: .service, isComplete: false)
}

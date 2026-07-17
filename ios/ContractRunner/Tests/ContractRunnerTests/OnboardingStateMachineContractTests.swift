import Foundation
import Testing
@testable import ContractRunner

struct OnboardingStateMachineContractTests {
    @Test("onboarding state machine fixtures")
    func onboardingStateMachineFixtures() throws {
        let fixtures = try FixtureLoader.listFixtureFiles(in: "onboarding_state_machine")
        for fixture in fixtures {
            let root = try FixtureLoader.readJSONObject(from: fixture)
            #expect(try requireString(root, "suite", fixture: fixture) == "onboarding_state_machine")

            let input = try requireObject(root, "input", fixture: fixture)
            let expected = try requireObject(root, "expected", fixture: fixture)

            let currentStepRaw = try requireString(input, "current_step", fixture: fixture)
            guard let currentStep = OnboardingStep(rawValue: currentStepRaw) else {
                throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): unknown step \(currentStepRaw)")
            }
            let connectedService = optionalString(input, "connected_service")
            let state = OnboardingState(currentStep: currentStep, connectedService: connectedService)

            let transition = advanceOnboarding(state)

            let expectedNextRaw = try requireString(expected, "next_step", fixture: fixture)
            guard let expectedNext = OnboardingStep(rawValue: expectedNextRaw) else {
                throw ContractTestError.invalidFixture("\(fixture.lastPathComponent): unknown step \(expectedNextRaw)")
            }
            let expectedComplete = try requireBool(expected, "is_complete", fixture: fixture)
            #expect(transition.nextStep == expectedNext)
            #expect(transition.isComplete == expectedComplete)
        }
    }
}

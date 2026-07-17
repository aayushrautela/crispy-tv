package com.crispy.tv.contracts

import com.crispy.tv.domain.account.OnboardingState
import com.crispy.tv.domain.account.OnboardingStep
import com.crispy.tv.domain.account.advanceOnboarding
import kotlin.test.Test
import kotlin.test.assertEquals

class OnboardingStateMachineContractTest {
    @Test
    fun onboardingStateMachineFixtures() {
        ContractTestSupport.fixtureFiles("onboarding_state_machine").forEach { path ->
            val root = ContractTestSupport.parseFixture(path)
            assertEquals("onboarding_state_machine", root.requireString("suite", path))
            val input = root.requireJsonObject("input", path)
            val expected = root.requireJsonObject("expected", path)

            val currentStep = OnboardingStep.valueOf(input.requireString("current_step", path))
            val connectedService = input.optionalString("connected_service", path)
            val state = OnboardingState(currentStep = currentStep, connectedService = connectedService)

            val transition = advanceOnboarding(state)

            val expectedNext = OnboardingStep.valueOf(expected.requireString("next_step", path))
            val expectedComplete = expected.requireBoolean("is_complete", path)
            assertEquals(expectedNext, transition.nextStep, "Next step mismatch in ${path.toDisplayPath()}")
            assertEquals(expectedComplete, transition.isComplete, "Complete mismatch in ${path.toDisplayPath()}")
        }
    }
}

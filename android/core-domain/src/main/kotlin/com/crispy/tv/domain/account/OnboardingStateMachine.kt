package com.crispy.tv.domain.account

enum class OnboardingStep { SERVICE, COMPLETE }

data class OnboardingState(
    val currentStep: OnboardingStep,
    val connectedService: String?
)

data class OnboardingTransition(
    val nextStep: OnboardingStep,
    val isComplete: Boolean
)

fun advanceOnboarding(state: OnboardingState): OnboardingTransition {
    return if (state.connectedService != null) {
        OnboardingTransition(OnboardingStep.COMPLETE, isComplete = true)
    } else {
        OnboardingTransition(OnboardingStep.SERVICE, isComplete = false)
    }
}

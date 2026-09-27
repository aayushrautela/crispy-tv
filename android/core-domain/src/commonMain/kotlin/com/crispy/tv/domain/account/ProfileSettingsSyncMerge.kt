package com.crispy.tv.domain.account

data class ProfileSettings(
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val syncProvider: String? = null,
    val onboardingStep: String? = null,
    val onboardingCompletedAtMs: Long? = null
)

fun mergeProfileSettings(local: ProfileSettings, server: ProfileSettings): ProfileSettings {
    return ProfileSettings(
        displayName = server.displayName ?: local.displayName,
        avatarUrl = server.avatarUrl ?: local.avatarUrl,
        syncProvider = server.syncProvider ?: local.syncProvider,
        onboardingStep = server.onboardingStep ?: local.onboardingStep,
        onboardingCompletedAtMs = server.onboardingCompletedAtMs ?: local.onboardingCompletedAtMs
    )
}

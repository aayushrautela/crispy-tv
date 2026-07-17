package com.crispy.tv.domain.account

const val MAX_PROFILE_NAME_LENGTH = 24

sealed interface ProfileNameResult {
    data class Valid(val normalized: String) : ProfileNameResult
    data class Invalid(val reason: String) : ProfileNameResult
}

fun validateProfileName(raw: String): ProfileNameResult {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ProfileNameResult.Invalid("blank")
    if (trimmed.length > MAX_PROFILE_NAME_LENGTH) return ProfileNameResult.Invalid("too_long")
    return ProfileNameResult.Valid(trimmed)
}

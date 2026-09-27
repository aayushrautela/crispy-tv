package com.crispy.tv.domain.account

data class ProfileSortInput(
    val id: String,
    val name: String,
    val isKids: Boolean,
    val lastUsedMs: Long?
)

fun sortProfiles(profiles: List<ProfileSortInput>): List<String> {
    return profiles
        .sortedWith(
            compareByDescending<ProfileSortInput> { it.isKids }
                .thenByDescending { it.lastUsedMs ?: Long.MIN_VALUE }
                .thenBy { it.id }
        )
        .map { it.id }
}

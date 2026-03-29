package com.crispy.tv.domain.repository

data class CrispySession(
    val accessToken: String,
    val userId: String,
)

interface SessionRepository {
    suspend fun ensureValidSession(): CrispySession?
}

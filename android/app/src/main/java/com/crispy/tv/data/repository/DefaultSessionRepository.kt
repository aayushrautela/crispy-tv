package com.crispy.tv.data.repository

import com.crispy.tv.accounts.SupabaseAccountClient
import com.crispy.tv.domain.repository.CrispySession
import com.crispy.tv.domain.repository.SessionRepository

class DefaultSessionRepository(
    private val supabaseAccountClient: SupabaseAccountClient,
) : SessionRepository {
    override suspend fun ensureValidSession(): CrispySession? {
        return supabaseAccountClient.ensureValidSession()?.let { session ->
            val userId = session.userId?.takeUnless(String::isBlank) ?: return null
            CrispySession(
                accessToken = session.accessToken,
                userId = userId,
            )
        }
    }
}

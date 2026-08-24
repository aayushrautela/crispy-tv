package com.crispy.tv.backend

import com.crispy.tv.accounts.ActiveProfileStore
import com.crispy.tv.accounts.SupabaseAccountClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class BackendContext(
    val accessToken: String,
    val profileId: String,
)

class BackendContextResolver(
    private val supabaseAccountClient: SupabaseAccountClient,
    private val activeProfileStore: ActiveProfileStore,
    private val backendClient: CrispyBackendClient,
) {
    @Volatile
    private var cachedContext: CachedBackendContext? = null
    private val resolveMutex = Mutex()

    suspend fun resolve(): BackendContext? {
        if (!supabaseAccountClient.isConfigured() || !backendClient.isConfigured()) {
            return null
        }

        val session = supabaseAccountClient.ensureValidSession() ?: return null
        val userId = session.userId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val accessToken = session.accessToken.trim().takeIf { it.isNotBlank() } ?: return null
        cachedContext?.takeIf { it.matches(userId, accessToken) }?.let {
            return BackendContext(accessToken = it.accessToken, profileId = it.profileId)
        }

        return resolveMutex.withLock {
            cachedContext?.takeIf { it.matches(userId, accessToken) }?.let {
                return@withLock BackendContext(accessToken = it.accessToken, profileId = it.profileId)
            }

            // The active profile is chosen explicitly by the user on the profile selector
            // (see ProfileSelectorRoute). We never auto-select a profile here, so a fresh
            // account lands on the selector instead of silently entering the first profile.
            val profileId = activeProfileStore.getActiveProfileId(userId).orEmpty().trim()
            if (profileId.isBlank()) {
                cachedContext = null
                return@withLock null
            }

            cachedContext = CachedBackendContext(
                userId = userId,
                accessToken = accessToken,
                profileId = profileId,
            )

            BackendContext(
                accessToken = accessToken,
                profileId = profileId,
            )
        }
    }

    fun clear() {
        cachedContext = null
    }

    private data class CachedBackendContext(
        val userId: String,
        val accessToken: String,
        val profileId: String,
    ) {
        fun matches(userId: String, accessToken: String): Boolean {
            return this.userId == userId && this.accessToken == accessToken && profileId.isNotBlank()
        }
    }
}

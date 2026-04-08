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
        cachedContext?.takeIf { it.matches(session.userId, session.accessToken) }?.let {
            return BackendContext(accessToken = it.accessToken, profileId = it.profileId)
        }

        return resolveMutex.withLock {
            cachedContext?.takeIf { it.matches(session.userId, session.accessToken) }?.let {
                return@withLock BackendContext(accessToken = it.accessToken, profileId = it.profileId)
            }

            var profileId = activeProfileStore.getActiveProfileId(session.userId).orEmpty().trim()
            if (profileId.isBlank()) {
                profileId = runCatching {
                    backendClient.getMe(session.accessToken).profiles.firstOrNull()?.id.orEmpty().trim()
                }.getOrDefault("")
                if (profileId.isNotBlank()) {
                    activeProfileStore.setActiveProfileId(session.userId, profileId)
                }
            }

            if (profileId.isBlank()) {
                cachedContext = null
                return@withLock null
            }

            cachedContext = CachedBackendContext(
                userId = session.userId,
                accessToken = session.accessToken,
                profileId = profileId,
            )

            BackendContext(
                accessToken = session.accessToken,
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

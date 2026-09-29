package com.crispy.tv.backend

import com.crispy.tv.accounts.AccountApi
import com.crispy.tv.accounts.ActiveProfileStore
import kotlin.concurrent.Volatile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class BackendContext(
    val accessToken: String,
    val profileId: String,
)

/**
 * Resolves the access token and profile the backend should be addressed with.
 *
 * An interface rather than a class, for the same reason [BackendApi] is: the
 * repositories that depend on it are ordinary logic with nothing Android about
 * them, and they cannot be exercised against a final class.
 * [CachingBackendContextResolver] is the only implementation.
 */
interface BackendContextResolver {
    suspend fun resolve(): BackendContext?

    /** Drops the cached context. Called on sign-out. */
    fun clear()
}

/**
 * [BackendContextResolver] with a per-session cache in front of the three
 * lookups it would otherwise repeat. The lock-free read before the mutex is a
 * fast path; the check inside the mutex is what makes concurrent resolves agree.
 */
class CachingBackendContextResolver(
    private val supabaseAccountClient: AccountApi,
    private val activeProfileStore: ActiveProfileStore,
    private val backendClient: BackendApi,
) : BackendContextResolver {
    @Volatile
    private var cachedContext: CachedBackendContext? = null
    private val resolveMutex = Mutex()

    override suspend fun resolve(): BackendContext? {
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

    override fun clear() {
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

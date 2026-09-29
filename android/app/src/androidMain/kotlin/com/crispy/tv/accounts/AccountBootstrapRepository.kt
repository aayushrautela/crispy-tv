package com.crispy.tv.accounts

import android.content.Context
import com.crispy.tv.backend.BackendApi
import com.crispy.tv.backend.BackendContextResolver
import com.crispy.tv.backend.Profile
import com.crispy.tv.images.clearImageCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class BootstrapResult(
    val signedIn: Boolean,
    val anonymous: Boolean,
    val onboardingComplete: Boolean,
    val session: Session?,
)

/**
 * The one account repository that stays in `androidMain`, for two reasons that
 * cannot be engineered around: [SecureTokenStore] reaches `AndroidKeyStore`,
 * and [clearImageCache] reaches Coil. Its siblings are in `commonMain` in
 * `AccountRepositories.kt`.
 */
class AccountBootstrapRepository(
    private val appContext: Context,
    private val supabase: AccountApi,
    private val backendContextResolver: BackendContextResolver,
    private val backendClient: BackendApi,
    private val activeProfileStore: ActiveProfileStore,
    private val tokenStore: SecureTokenStore,
) {
    suspend fun bootstrap(): BootstrapResult {
        val session = supabase.ensureValidSession()
        if (session == null) {
            return BootstrapResult(signedIn = false, anonymous = false, onboardingComplete = false, session = null)
        }
        // Onboarding is complete once a backend context resolves: that requires a valid
        // session *and* an active profile. The Trakt/Simkl sync provider is an account
        // setting, not an onboarding gate.
        val onboardingComplete = backendContextResolver.resolve() != null
        return BootstrapResult(
            signedIn = true,
            anonymous = session.anonymous,
            onboardingComplete = onboardingComplete,
            session = session,
        )
    }

    /**
     * Creates the account's first (primary/admin) profile via POST /v1/account/bootstrap and
     * marks it active. Idempotent server-side, so retrying is safe. Used both by the signup
     * wizard (right after Supabase sign-up) and by the "Finish setting up" gate (sign-in of an
     * account that has no profile yet).
     */
    suspend fun bootstrapPrimaryProfile(
        name: String,
        interfaceLanguage: String,
        avatarUrl: String,
        region: String? = null,
    ): Profile {
        val session = supabase.ensureValidSession() ?: throw IllegalStateException("Not signed in.")
        val profile = backendClient.bootstrapAccount(session.accessToken, name, interfaceLanguage, avatarUrl, region)
        session.userId?.takeIf { it.isNotBlank() }?.let { userId ->
            activeProfileStore.setActiveProfileId(userId, profile.id)
        }
        return profile
    }

    private val signOutMutex = Mutex()

    suspend fun signOut() {
        // One locked sequence: revoke server-side first, then always wipe local state.
        // Running un-awaited (e.g. fire-and-forget) before re-bootstrapping is what previously
        // let a stale session survive and kept the old home mounted.
        signOutMutex.withLock {
            val userId = supabase.currentSession()?.userId?.takeIf { it.isNotBlank() }
            runCatching { supabase.signOut() }
            tokenStore.clear()
            // The injected store, not a second lookup through SupabaseServicesProvider: it is
            // the same instance the constructor was given, and reaching for the global here
            // hid the one dependency this class actually has.
            userId?.let { activeProfileStore.clear(it) }
            backendContextResolver.clear()
            clearImageCache(appContext)
        }
    }
}

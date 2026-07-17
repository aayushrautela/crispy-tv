package com.crispy.tv.accounts

import com.crispy.tv.accounts.SupabaseAccountClient.Session
import com.crispy.tv.backend.BackendContextResolver
import com.crispy.tv.domain.account.OnboardingState
import com.crispy.tv.domain.account.OnboardingStep
import com.crispy.tv.domain.account.advanceOnboarding
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class BootstrapResult(
    val signedIn: Boolean,
    val anonymous: Boolean,
    val onboardingComplete: Boolean,
    val session: Session?,
)

class AccountBootstrapRepository(
    private val supabase: SupabaseAccountClient,
    private val backendContextResolver: BackendContextResolver,
    private val onboardingRepository: OnboardingRepository,
) {
    suspend fun bootstrap(): BootstrapResult {
        val session = supabase.ensureValidSession()
        if (session == null) {
            return BootstrapResult(signedIn = false, anonymous = false, onboardingComplete = false, session = null)
        }
        val context = backendContextResolver.resolve()
        val onboardingComplete = if (context != null) {
            onboardingRepository.isOnboardingComplete(context.accessToken)
        } else {
            false
        }
        return BootstrapResult(
            signedIn = true,
            anonymous = session.anonymous,
            onboardingComplete = onboardingComplete,
            session = session,
        )
    }

    fun signOut() {
        supabase.signOut()
        backendContextResolver.clear()
    }
}

class OnboardingRepository(
    private val backendContextResolver: BackendContextResolver,
    private val backendClient: com.crispy.tv.backend.CrispyBackendClient,
) {
    private val cacheMutex = Mutex()
    private var cachedStep: OnboardingStep? = null

    suspend fun getState(accessToken: String): OnboardingState {
        val context = backendContextResolver.resolve() ?: return OnboardingState(OnboardingStep.SERVICE, null)
        val profileId = context.profileId
        val settings = backendClient.getProfileSettings(accessToken, profileId).settings
        val connectedService = settings["syncProvider"]?.trim().takeIf { it.isNotBlank() }
        val step = if (connectedService != null) OnboardingStep.COMPLETE else OnboardingStep.SERVICE
        cacheMutex.withLock { cachedStep = step }
        return OnboardingState(currentStep = step, connectedService = connectedService)
    }

    suspend fun isOnboardingComplete(accessToken: String): Boolean {
        return getState(accessToken).connectedService != null
    }

    suspend fun markServiceConnected(accessToken: String, provider: String): OnboardingStep {
        val context = backendContextResolver.resolve() ?: return OnboardingStep.SERVICE
        val profileId = context.profileId
        backendClient.patchProfileSettings(
            accessToken = accessToken,
            profileId = profileId,
            settings = mapOf("syncProvider" to provider.trim()),
        )
        cacheMutex.withLock { cachedStep = OnboardingStep.COMPLETE }
        return OnboardingStep.COMPLETE
    }
}

class ProfileRepository(
    private val backendContextResolver: BackendContextResolver,
    private val backendClient: com.crispy.tv.backend.CrispyBackendClient,
) {
    suspend fun listProfiles(accessToken: String): List<com.crispy.tv.backend.CrispyBackendClient.Profile> {
        return backendClient.listProfiles(accessToken)
    }

    suspend fun createProfile(
        accessToken: String,
        name: String,
        isKids: Boolean,
        avatarKey: String?,
    ): com.crispy.tv.backend.CrispyBackendClient.Profile {
        return backendClient.createProfile(
            accessToken = accessToken,
            name = name,
            isKids = isKids,
            avatarKey = avatarKey,
        )
    }

    suspend fun updateProfile(
        accessToken: String,
        profileId: String,
        name: String?,
        isKids: Boolean?,
        avatarKey: String?,
    ): com.crispy.tv.backend.CrispyBackendClient.Profile {
        return backendClient.updateProfile(
            accessToken = accessToken,
            profileId = profileId,
            input = com.crispy.tv.backend.CrispyBackendClient.UpdateProfileInput(
                name = name,
                isKids = isKids,
                avatarKey = avatarKey,
            ),
        )
    }

    suspend fun patchSettings(
        accessToken: String,
        profileId: String,
        settings: Map<String, String>,
    ) {
        backendClient.patchProfileSettings(accessToken, profileId, settings)
    }
}

class AccountSettingsRepository(
    private val backendClient: com.crispy.tv.backend.CrispyBackendClient,
) {
    suspend fun getAccountSettings(accessToken: String): com.crispy.tv.backend.CrispyBackendClient.AccountSettings {
        return backendClient.getAccountSettings(accessToken)
    }

    suspend fun updateEmail(accessToken: String, email: String): com.crispy.tv.backend.CrispyBackendClient.AccountSettings {
        return backendClient.patchAccountSettings(accessToken = accessToken, email = email)
    }

    suspend fun changePassword(
        accessToken: String,
        currentPassword: String,
        newPassword: String,
    ): com.crispy.tv.backend.CrispyBackendClient.AccountSettings {
        return backendClient.patchAccountSettings(
            accessToken = accessToken,
            currentPassword = currentPassword,
            newPassword = newPassword,
        )
    }

    suspend fun deleteAccount(accessToken: String): Boolean {
        return backendClient.deleteAccount(accessToken)
    }
}

class PendingProviderAuthStore(private val context: android.content.Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

    fun put(provider: String, state: String) {
        prefs.edit().putString(KEY_PROVIDER, provider.trim()).putString(KEY_STATE, state.trim()).apply()
    }

    fun consume(): Pair<String, String>? {
        val provider = prefs.getString(KEY_PROVIDER, null) ?: return null
        val state = prefs.getString(KEY_STATE, null) ?: return null
        prefs.edit().clear().apply()
        return provider to state
    }

    fun peek(): Pair<String, String>? {
        val provider = prefs.getString(KEY_PROVIDER, null) ?: return null
        val state = prefs.getString(KEY_STATE, null) ?: return null
        return provider to state
    }

    private companion object {
        private const val PREFS_NAME = "pending_provider_auth"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_STATE = "state"
    }
}

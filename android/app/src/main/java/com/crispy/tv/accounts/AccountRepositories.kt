package com.crispy.tv.accounts

import com.crispy.tv.accounts.SupabaseAccountClient.Session
import com.crispy.tv.backend.BackendContextResolver

data class BootstrapResult(
    val signedIn: Boolean,
    val anonymous: Boolean,
    val onboardingComplete: Boolean,
    val session: Session?,
)

class AccountBootstrapRepository(
    private val supabase: SupabaseAccountClient,
    private val backendContextResolver: BackendContextResolver,
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

    suspend fun signOut() {
        supabase.signOut()
        backendContextResolver.clear()
    }
}

/**
 * Drives Trakt/Simkl sync connections via the backend import-connections API.
 * Connect/reconnect go through OAuth: [startImport] returns an `authUrl` the
 * client opens in the browser; the server's `/v1/imports/:provider/callback`
 * then redirects back to the app's deep link (`crispytv://oauth-callback`).
 */
class SyncProviderRepository(
    private val backendContextResolver: BackendContextResolver,
    private val backendClient: com.crispy.tv.backend.CrispyBackendClient,
) {
    suspend fun getConnectedProvider(accessToken: String): String? {
        val context = backendContextResolver.resolve() ?: return null
        val states = backendClient
            .listImportConnections(accessToken, context.profileId)
            .providerStates
        return states
            .firstOrNull { it.connectionState.equals("connected", ignoreCase = true) }
            ?.provider
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    suspend fun startImport(
        accessToken: String,
        provider: com.crispy.tv.backend.CrispyBackendClient.ImportProvider,
        action: String,
        returnTo: String,
    ): com.crispy.tv.backend.CrispyBackendClient.StartImportResult {
        val context = backendContextResolver.resolve()
            ?: throw IllegalStateException("No active profile.")
        return backendClient.startImport(
            accessToken = accessToken,
            profileId = context.profileId,
            provider = provider,
            action = action,
            clientId = ANDROID_CLIENT_ID,
            returnTo = returnTo,
        )
    }

    suspend fun disconnectImportConnection(
        accessToken: String,
        provider: com.crispy.tv.backend.CrispyBackendClient.ImportProvider,
    ) {
        val context = backendContextResolver.resolve() ?: return
        backendClient.disconnectImportConnection(accessToken, context.profileId, provider)
    }

    private companion object {
        const val ANDROID_CLIENT_ID = "crispy-android"
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

    suspend fun patchSettings(
        accessToken: String,
        settings: Map<String, String>,
    ): com.crispy.tv.backend.CrispyBackendClient.AccountSettings {
        return backendClient.patchAccountSettings(accessToken = accessToken, settings = settings)
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

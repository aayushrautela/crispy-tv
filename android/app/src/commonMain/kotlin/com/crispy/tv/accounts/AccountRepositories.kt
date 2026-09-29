package com.crispy.tv.accounts

import com.crispy.tv.backend.AccountSettings
import com.crispy.tv.backend.BackendApi
import com.crispy.tv.backend.BackendContextResolver
import com.crispy.tv.backend.ImportProvider
import com.crispy.tv.backend.Profile
import com.crispy.tv.backend.StartImportResult
import com.crispy.tv.backend.UpdateProfileInput
import com.crispy.tv.platform.KeyValueStore

/**
 * Drives Trakt/Simkl sync connections via the backend import-connections API.
 * Connect/reconnect go through OAuth: [startImport] returns an `authUrl` the
 * client opens in the browser; the server's `/v1/imports/:provider/callback`
 * then redirects back to the app's deep link (`crispytv://oauth-callback`).
 */
class SyncProviderRepository(
    private val backendContextResolver: BackendContextResolver,
    private val backendClient: BackendApi,
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
        provider: ImportProvider,
        action: String,
        returnTo: String,
    ): StartImportResult {
        val context = backendContextResolver.resolve()
            ?: throw IllegalStateException("No active profile.")
        return backendClient.startImport(
            accessToken = accessToken,
            profileId = context.profileId,
            provider = provider,
            action = action,
            clientId = CLIENT_ID,
            returnTo = returnTo,
        )
    }

    suspend fun disconnectImportConnection(
        accessToken: String,
        provider: ImportProvider,
    ) {
        val context = backendContextResolver.resolve() ?: return
        backendClient.disconnectImportConnection(accessToken, context.profileId, provider)
    }

    private companion object {
        const val CLIENT_ID = "crispy-android"
    }
}

class ProfileRepository(
    private val backendContextResolver: BackendContextResolver,
    private val backendClient: BackendApi,
) {
    suspend fun listProfiles(accessToken: String): List<Profile> {
        return backendClient.listProfiles(accessToken)
    }

    suspend fun createProfile(
        accessToken: String,
        name: String,
        isKids: Boolean,
        avatarKey: String?,
        interfaceLanguage: String? = null,
    ): Profile {
        return backendClient.createProfile(
            accessToken = accessToken,
            name = name,
            isKids = isKids,
            avatarKey = avatarKey,
            interfaceLanguage = interfaceLanguage,
        )
    }

    suspend fun updateProfile(
        accessToken: String,
        profileId: String,
        name: String?,
        isKids: Boolean?,
        avatarKey: String?,
    ): Profile {
        return backendClient.updateProfile(
            accessToken = accessToken,
            profileId = profileId,
            input = UpdateProfileInput(
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
    private val backendClient: BackendApi,
) {
    suspend fun getAccountSettings(accessToken: String): AccountSettings {
        return backendClient.getAccountSettings(accessToken)
    }

    suspend fun patchSettings(
        accessToken: String,
        settings: Map<String, String>,
    ): AccountSettings {
        return backendClient.patchAccountSettings(accessToken = accessToken, settings = settings)
    }

    suspend fun deleteAccount(accessToken: String): Boolean {
        return backendClient.deleteAccount(accessToken)
    }
}

/**
 * Holds the single OAuth `state` / `provider` pair between the browser round
 * trip: the redirect comes back as a deep link with no access to this process's
 * memory, so the pair has to survive in storage rather than in a field.
 *
 * The store owns the *shape* of the value; [store] owns where it lives. It is a
 * pair because a `state` is only meaningful together with the provider it was
 * issued for, and [consume] clears both together for the same reason.
 */
class PendingProviderAuthStore(private val store: KeyValueStore) {
    fun put(provider: String, state: String) {
        store.putString(KEY_PROVIDER, provider.trim())
        store.putString(KEY_STATE, state.trim())
    }

    /** Reads the pair and clears it. A `state` may be redeemed exactly once. */
    fun consume(): Pair<String, String>? {
        val pair = peek() ?: return null
        store.clear()
        return pair
    }

    fun peek(): Pair<String, String>? {
        val provider = store.getString(KEY_PROVIDER) ?: return null
        val state = store.getString(KEY_STATE) ?: return null
        return provider to state
    }

    private companion object {
        private const val KEY_PROVIDER = "provider"
        private const val KEY_STATE = "state"
    }
}

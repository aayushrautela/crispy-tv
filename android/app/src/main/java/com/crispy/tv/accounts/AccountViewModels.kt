package com.crispy.tv.accounts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crispy.tv.avatar.AvatarUrlResolver
import com.crispy.tv.domain.account.normalizeLanguageCode
import com.crispy.tv.domain.account.validateProfileName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val isBusy: Boolean = false,
    val email: String = "",
    val password: String = "",
    val displayName: String = "",
    val interfaceLanguage: String = "en",
    val avatarId: String = "avatar_01",
    val error: String? = null,
    val info: String? = null,
    val signedIn: Boolean = false,
) {
    val avatarUrl: String
        get() = AvatarUrlResolver.builtInAvatarUrl(avatarId)
}

class AuthViewModel internal constructor(
    private val supabase: SupabaseAccountClient,
    private val bootstrapRepository: AccountBootstrapRepository,
) : ViewModel() {
    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(AuthViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return AuthViewModel(
                            supabase = SupabaseServicesProvider.accountClient(appContext),
                            bootstrapRepository = SupabaseServicesProvider.bootstrapRepository(appContext),
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }

    private val _state = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _state.asStateFlow()

    fun onEmailChange(value: String) = _state.update { it.copy(email = value, error = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }
    fun onNameChange(value: String) = _state.update { it.copy(displayName = value, error = null) }
    fun onLanguageChange(value: String) = _state.update { it.copy(interfaceLanguage = value, error = null) }
    fun onAvatarIdChange(value: String) = _state.update { it.copy(avatarId = value) }

    fun signIn() {
        val email = _state.value.email.trim()
        val password = _state.value.password
        if (email.isBlank() || password.isBlank()) {
            _state.update { it.copy(error = "Enter email and password.") }
            return
        }
        runAccountCall {
            supabase.signInWithEmail(email, password)
            _state.update { it.copy(signedIn = true, error = null) }
        }
    }

    fun signUp() {
        val email = _state.value.email.trim()
        val password = _state.value.password
        if (email.isBlank() || password.isBlank()) {
            _state.update { it.copy(error = "Enter email and password.") }
            return
        }
        val rawName = _state.value.displayName.trim().ifBlank { email.substringBefore('@') }
        val name = when (val result = validateProfileName(rawName)) {
            is com.crispy.tv.domain.account.ProfileNameResult.Valid -> result.normalized
            is com.crispy.tv.domain.account.ProfileNameResult.Invalid -> {
                _state.update { it.copy(error = result.reason) }
                return
            }
        }
        val interfaceLanguage = normalizeLanguageCode(_state.value.interfaceLanguage) ?: "en"
        val avatarId = _state.value.avatarId
        runAccountCall {
            val signUpResult = supabase.signUpWithEmail(email, password)
            if (signUpResult.session != null) {
                bootstrapRepository.bootstrapPrimaryProfile(name, interfaceLanguage, avatarId, region = null)
                _state.update { it.copy(signedIn = true, error = null, info = null) }
            } else {
                _state.update { it.copy(signedIn = false, info = signUpResult.message, error = null) }
            }
        }
    }

    private fun runAccountCall(block: suspend () -> Unit) {
        _state.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            runCatching { block() }
                .onFailure { error ->
                    _state.update { it.copy(isBusy = false, error = error.message ?: "Request failed.") }
                }
                .onSuccess {
                    _state.update { it.copy(isBusy = false) }
                }
        }
    }
}

data class ProfileListItem(
    val id: String,
    val name: String,
    val isKids: Boolean,
    val avatarUrl: String?,
)

data class ProfileListUiState(
    val isBusy: Boolean = false,
    val profiles: List<ProfileListItem> = emptyList(),
    val error: String? = null,
    val dialogOpen: Boolean = false,
    val dialogName: String = "",
    val dialogIsKids: Boolean = false,
    val dialogAvatarId: String = "avatar_01",
    val dialogError: String? = null,
    val justSelected: Boolean = false,
)

class ProfileListViewModel internal constructor(
    private val bootstrapRepository: AccountBootstrapRepository,
    private val profileRepository: ProfileRepository,
    private val activeProfileStore: ActiveProfileStore,
) : ViewModel() {
    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(ProfileListViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                    return ProfileListViewModel(
                        bootstrapRepository = SupabaseServicesProvider.bootstrapRepository(appContext),
                        profileRepository = SupabaseServicesProvider.profileRepository(appContext),
                        activeProfileStore = SupabaseServicesProvider.activeProfileStore(appContext),
                    ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }

    private val _state = MutableStateFlow(ProfileListUiState())
    val uiState: StateFlow<ProfileListUiState> = _state.asStateFlow()

    fun load() {
        _state.update { it.copy(isBusy = true, error = null, justSelected = false) }
        viewModelScope.launch {
            runCatching {
                val session = bootstrapRepository.bootstrap().session ?: throw IllegalStateException("Not signed in.")
                profileRepository.listProfiles(session.accessToken)
            }.onSuccess { profiles ->
                val items = profiles
                    .map { p -> ProfileListItem(id = p.id, name = p.name, isKids = p.isKids, avatarUrl = resolveAvatar(p.avatarKey)) }
                _state.update { it.copy(isBusy = false, profiles = items) }
            }.onFailure { error ->
                _state.update { it.copy(isBusy = false, error = error.message ?: "Failed to load profiles.") }
            }
        }
    }

    fun openCreateDialog() = _state.update { it.copy(dialogOpen = true, dialogName = "", dialogIsKids = false, dialogAvatarId = "avatar_01", dialogError = null) }
    fun dismissDialog() = _state.update { it.copy(dialogOpen = false, dialogError = null) }
    fun onNameChange(value: String) = _state.update { it.copy(dialogName = value, dialogError = null) }
    fun onKidsToggle(value: Boolean) = _state.update { it.copy(dialogIsKids = value) }
    fun onAvatarIdChange(value: String) = _state.update { it.copy(dialogAvatarId = value) }

    fun createProfile(name: String, isKids: Boolean, avatarId: String) {
        val normalized = when (val result = validateProfileName(name)) {
            is com.crispy.tv.domain.account.ProfileNameResult.Valid -> result.normalized
            is com.crispy.tv.domain.account.ProfileNameResult.Invalid -> {
                _state.update { it.copy(dialogError = result.reason) }
                return
            }
        }
        val avatarKey = avatarId.trim().ifBlank { "avatar_01" }
        _state.update { it.copy(isBusy = true, dialogError = null) }
        viewModelScope.launch {
            runCatching {
                val session = bootstrapRepository.bootstrap().session ?: throw IllegalStateException("Not signed in.")
                profileRepository.createProfile(session.accessToken, normalized, isKids, avatarKey)
            }            .onSuccess {
                _state.update { it.copy(isBusy = false, dialogOpen = false) }
                load()
            }.onFailure { error ->
                _state.update { it.copy(isBusy = false, dialogError = error.message ?: "Failed to create profile.") }
            }
        }
    }

    /**
     * Activates an existing profile and signals the bootstrap gate to enter the app.
     * Used by the post-signup profile selector ("Who's watching?").
     */
    fun selectProfile(id: String) {
        if (id.isBlank()) return
        _state.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            runCatching {
                val session = bootstrapRepository.bootstrap().session ?: throw IllegalStateException("Not signed in.")
                session.userId?.let { userId -> activeProfileStore.setActiveProfileId(userId, id) }
                    ?: throw IllegalStateException("Missing user id.")
            }.onSuccess {
                _state.update { it.copy(isBusy = false, justSelected = true) }
            }.onFailure { error ->
                _state.update { it.copy(isBusy = false, error = error.message ?: "Failed to select profile.") }
            }
        }
    }

    private fun resolveAvatar(avatarKey: String?): String? {
        return AvatarUrlResolver.resolveAvatarUrl(avatarKey)
    }

    /**
     * First-run "Finish setting up your profile" flow (mirrors the web's ProfileSetup gate):
     * creates the initial profile, activates it, and signals the bootstrap gate to enter the app.
     */
    fun finishSetup(name: String, language: String, avatarId: String) {
        val normalized = when (val result = validateProfileName(name)) {
            is com.crispy.tv.domain.account.ProfileNameResult.Valid -> result.normalized
            is com.crispy.tv.domain.account.ProfileNameResult.Invalid -> {
                _state.update { it.copy(error = result.reason) }
                return
            }
        }
        val avatarKey = avatarId.trim().ifBlank { "avatar_01" }
        val interfaceLanguage = normalizeLanguageCode(language) ?: "en"
        _state.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            runCatching {
                val session = bootstrapRepository.bootstrap().session
                    ?: throw IllegalStateException("Not signed in.")
                bootstrapRepository.bootstrapPrimaryProfile(normalized, interfaceLanguage, avatarKey, region = null)
            }.onSuccess {
                _state.update { it.copy(isBusy = false, justSelected = true) }
            }.onFailure { error ->
                _state.update { it.copy(isBusy = false, error = error.message ?: "Failed to create profile.") }
            }
        }
    }
}

data class AccountSettingsUiState(
    val isBusy: Boolean = false,
    val pricingTier: String? = null,
    val hasMdbListAccess: Boolean = false,
    val error: String? = null,
    val statusMessage: String? = null,
    val deleted: Boolean = false,
    val syncProvider: String? = null,
)

class AccountSettingsViewModel internal constructor(
    private val appContext: Context,
    private val bootstrapRepository: AccountBootstrapRepository,
    private val accountSettingsRepository: AccountSettingsRepository,
    private val syncProviderRepository: SyncProviderRepository,
    private val pendingProviderAuthStore: PendingProviderAuthStore,
) : ViewModel() {
    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(AccountSettingsViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return AccountSettingsViewModel(
                            appContext = appContext,
                            bootstrapRepository = SupabaseServicesProvider.bootstrapRepository(appContext),
                            accountSettingsRepository = SupabaseServicesProvider.accountSettingsRepository(appContext),
                            syncProviderRepository = SupabaseServicesProvider.syncProviderRepository(appContext),
                            pendingProviderAuthStore = SupabaseServicesProvider.pendingProviderAuthStore(appContext),
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }

        // Must match the Android entry in the server's IMPORT_OAUTH_ALLOWED_RETURN_URIS
        // allowlist and the deep link registered in AndroidManifest.xml.
        private const val OAUTH_RETURN_TO = "crispytv://oauth-callback"
    }

    private val _state = MutableStateFlow(AccountSettingsUiState())
    val uiState: StateFlow<AccountSettingsUiState> = _state.asStateFlow()

    fun load() {
        _state.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            runCatching {
                val session = bootstrapRepository.bootstrap().session ?: throw IllegalStateException("Not signed in.")
                val settings = accountSettingsRepository.getAccountSettings(session.accessToken)
                val syncProvider = syncProviderRepository.getConnectedProvider(session.accessToken)
                Triple(settings, syncProvider, session.accessToken)
            }.onSuccess { (settings, syncProvider, _) ->
                _state.update {
                    it.copy(
                        isBusy = false,
                        pricingTier = settings.pricingTier,
                        hasMdbListAccess = settings.hasMdbListAccess,
                        syncProvider = syncProvider,
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(isBusy = false, error = error.message ?: "Failed to load account settings.") }
            }
        }
    }

    /**
     * Called on resume to consume a pending provider OAuth callback parked by the deep link
     * (see [com.crispy.tv.MainActivity.handleDeepLink]). The connection was already persisted
     * server-side during the callback; just refresh the state.
     */
    fun consumePendingProviderAuth() {
        val pending = pendingProviderAuthStore.consume() ?: return
        if (pending.first.isBlank()) return
        load()
    }

    fun startImport(provider: String) {
        val importProvider = parseImportProvider(provider) ?: return
        _state.update { it.copy(isBusy = true, error = null, statusMessage = null) }
        viewModelScope.launch {
            runCatching {
                val session = bootstrapRepository.bootstrap().session
                    ?: throw IllegalStateException("Not signed in.")
                val result = syncProviderRepository.startImport(
                    accessToken = session.accessToken,
                    provider = importProvider,
                    action = if (importProvider.apiValue == uiState.value.syncProvider) "reconnect" else "connect",
                    returnTo = OAUTH_RETURN_TO,
                )
                val authUrl = result.authUrl?.takeIf { it.isNotBlank() }
                if (authUrl != null) launchBrowser(authUrl)
            }.onSuccess {
                _state.update {
                    it.copy(
                        isBusy = false,
                        statusMessage = "Complete ${importProvider.apiValue} sign-in in your browser.",
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(isBusy = false, error = error.message ?: "Failed to start ${importProvider.apiValue} connection.") }
            }
        }
    }

    fun disconnectSyncProvider() {
        val provider = uiState.value.syncProvider?.let { parseImportProvider(it) } ?: return
        _state.update { it.copy(isBusy = true, error = null, statusMessage = null) }
        viewModelScope.launch {
            runCatching {
                val session = bootstrapRepository.bootstrap().session
                    ?: throw IllegalStateException("Not signed in.")
                syncProviderRepository.disconnectImportConnection(session.accessToken, provider)
            }.onSuccess {
                _state.update { it.copy(isBusy = false, syncProvider = null, statusMessage = null) }
            }.onFailure { error ->
                _state.update { it.copy(isBusy = false, error = error.message ?: "Failed to disconnect sync.") }
            }
        }
    }

    private fun launchBrowser(url: String) {
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse(url),
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }

    private fun parseImportProvider(value: String): com.crispy.tv.backend.CrispyBackendClient.ImportProvider? {
        val normalized = value.trim().lowercase()
        return when (normalized) {
            "trakt" -> com.crispy.tv.backend.CrispyBackendClient.ImportProvider.TRAKT
            "simkl" -> com.crispy.tv.backend.CrispyBackendClient.ImportProvider.SIMKL
            else -> null
        }
    }

    fun deleteAccount() {
        _state.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            runCatching {
                val session = bootstrapRepository.bootstrap().session ?: throw IllegalStateException("Not signed in.")
                accountSettingsRepository.deleteAccount(session.accessToken)
            }.onSuccess {
                bootstrapRepository.signOut()
                _state.update { it.copy(isBusy = false, deleted = true) }
            }.onFailure { error ->
                _state.update { it.copy(isBusy = false, error = error.message ?: "Failed to delete account.") }
            }
        }
    }
}

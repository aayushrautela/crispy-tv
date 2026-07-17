package com.crispy.tv.accounts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crispy.tv.avatar.AvatarUrlResolver
import com.crispy.tv.domain.account.DicebearStyle
import com.crispy.tv.domain.account.normalizeLanguageCode
import com.crispy.tv.domain.account.validateProfileName
import com.crispy.tv.domain.account.validateSignupMetadata
import com.crispy.tv.domain.account.SignupMetadata
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
    val avatarStyle: String = DicebearStyle.THUMBS.apiValue,
    val referralCode: String = "",
    val error: String? = null,
    val signedIn: Boolean = false,
) {
    val avatarUrl: String
        get() = AvatarUrlResolver.dicebearUrl(
            style = DicebearStyle.fromApiValue(avatarStyle) ?: DicebearStyle.THUMBS,
            seed = displayName.ifBlank { email },
        )
}

class AuthViewModel internal constructor(
    private val supabase: SupabaseAccountClient,
) : ViewModel() {
    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(AuthViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return AuthViewModel(supabase = SupabaseServicesProvider.accountClient(appContext)) as T
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
    fun onAvatarStyleChange(value: String) = _state.update { it.copy(avatarStyle = value) }
    fun onReferralChange(value: String) = _state.update { it.copy(referralCode = value, error = null) }

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
        val name = _state.value.displayName.trim().ifBlank { email.substringBefore('@') }
        val language = normalizeLanguageCode(_state.value.interfaceLanguage) ?: "en"
        val avatarUrl = _state.value.avatarUrl
        val referralCode = _state.value.referralCode.trim().ifBlank { null }
        val metadata = SignupMetadata(
            name = name,
            interfaceLanguage = language,
            region = null,
            avatarUrl = avatarUrl,
            referralCode = referralCode,
        )
        val validation = validateSignupMetadata(metadata)
        if (!validation.isComplete) {
            _state.update { it.copy(error = "Missing: ${validation.missing.joinToString()}") }
            return
        }
        val metadataMap = mapOf(
            "full_name" to metadata.name,
            "interfaceLanguage" to metadata.interfaceLanguage,
            "avatarUrl" to metadata.avatarUrl,
            "referralCode" to metadata.referralCode,
        )
        runAccountCall {
            supabase.signUpWithEmail(email, password, metadataMap)
            _state.update { it.copy(signedIn = true, error = null) }
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
    val dialogError: String? = null,
    val justSaved: Boolean = false,
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
        _state.update { it.copy(isBusy = true, error = null) }
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

    fun openCreateDialog() = _state.update { it.copy(dialogOpen = true, dialogName = "", dialogIsKids = false, dialogError = null, justSaved = false) }
    fun dismissDialog() = _state.update { it.copy(dialogOpen = false, dialogError = null) }
    fun onNameChange(value: String) = _state.update { it.copy(dialogName = value, dialogError = null) }
    fun onKidsToggle(value: Boolean) = _state.update { it.copy(dialogIsKids = value) }

    fun createProfile(name: String, isKids: Boolean) {
        val normalized = when (val result = validateProfileName(name)) {
            is com.crispy.tv.domain.account.ProfileNameResult.Valid -> result.normalized
            is com.crispy.tv.domain.account.ProfileNameResult.Invalid -> {
                _state.update { it.copy(dialogError = result.reason) }
                return
            }
        }
        val avatarKey = AvatarUrlResolver.dicebearUrl(seed = normalized)
        _state.update { it.copy(isBusy = true, dialogError = null) }
        viewModelScope.launch {
            runCatching {
                val session = bootstrapRepository.bootstrap().session ?: throw IllegalStateException("Not signed in.")
                val newProfile = profileRepository.createProfile(session.accessToken, normalized, isKids, avatarKey)
                session.userId?.let { userId -> activeProfileStore.setActiveProfileId(userId, newProfile.id) }
            }.onSuccess {
                _state.update { it.copy(isBusy = false, dialogOpen = false, justSaved = true) }
                load()
            }.onFailure { error ->
                _state.update { it.copy(isBusy = false, dialogError = error.message ?: "Failed to create profile.") }
            }
        }
    }

    private fun resolveAvatar(avatarKey: String?): String? {
        return AvatarUrlResolver.resolveAvatarUrl(avatarKey)
    }
}

data class AccountSettingsUiState(
    val isBusy: Boolean = false,
    val email: String = "",
    val hasPassword: Boolean = false,
    val referralCode: String? = null,
    val error: String? = null,
    val statusMessage: String? = null,
    val deleted: Boolean = false,
    val syncProvider: String? = null,
)

class AccountSettingsViewModel internal constructor(
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
                        email = settings.email.orEmpty(),
                        hasPassword = settings.hasPassword,
                        referralCode = settings.referralCode,
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
     * (see [com.crispy.tv.MainActivity.handleDeepLink]). If a provider is pending, we mark it
     * as connected and refresh the state.
     */
    fun consumePendingProviderAuth() {
        val pending = pendingProviderAuthStore.consume() ?: return
        val provider = pending.first.trim().takeIf { it.isNotBlank() } ?: return
        setSyncProvider(provider)
    }

    fun setSyncProvider(provider: String) {
        val normalized = provider.trim()
        if (normalized.isBlank()) return
        _state.update { it.copy(isBusy = true, error = null, statusMessage = null) }
        viewModelScope.launch {
            runCatching {
                val session = bootstrapRepository.bootstrap().session ?: throw IllegalStateException("Not signed in.")
                syncProviderRepository.setConnectedProvider(session.accessToken, normalized)
            }.onSuccess {
                _state.update { it.copy(isBusy = false, syncProvider = normalized, statusMessage = "Sync connected.") }
            }.onFailure { error ->
                _state.update { it.copy(isBusy = false, error = error.message ?: "Failed to connect sync.") }
            }
        }
    }

    fun clearSyncProvider() {
        _state.update { it.copy(isBusy = true, error = null, statusMessage = null) }
        viewModelScope.launch {
            runCatching {
                val session = bootstrapRepository.bootstrap().session ?: throw IllegalStateException("Not signed in.")
                syncProviderRepository.clearConnectedProvider(session.accessToken)
            }.onSuccess {
                _state.update { it.copy(isBusy = false, syncProvider = null, statusMessage = "Sync disconnected.") }
            }.onFailure { error ->
                _state.update { it.copy(isBusy = false, error = error.message ?: "Failed to disconnect sync.") }
            }
        }
    }

    fun updateEmail(email: String) {
        val trimmed = email.trim()
        if (trimmed.isBlank()) {
            _state.update { it.copy(error = "Email cannot be blank.") }
            return
        }
        _state.update { it.copy(isBusy = true, error = null, statusMessage = null) }
        viewModelScope.launch {
            runCatching {
                val session = bootstrapRepository.bootstrap().session ?: throw IllegalStateException("Not signed in.")
                accountSettingsRepository.updateEmail(session.accessToken, trimmed)
            }.onSuccess { settings ->
                _state.update { it.copy(isBusy = false, email = settings.email.orEmpty(), statusMessage = "Email updated.") }
            }.onFailure { error ->
                _state.update { it.copy(isBusy = false, error = error.message ?: "Failed to update email.") }
            }
        }
    }

    fun changePassword(current: String, new: String) {
        if (current.isBlank() || new.isBlank()) {
            _state.update { it.copy(error = "Enter current and new password.") }
            return
        }
        _state.update { it.copy(isBusy = true, error = null, statusMessage = null) }
        viewModelScope.launch {
            runCatching {
                val session = bootstrapRepository.bootstrap().session ?: throw IllegalStateException("Not signed in.")
                accountSettingsRepository.changePassword(session.accessToken, current, new)
            }.onSuccess {
                _state.update { it.copy(isBusy = false, statusMessage = "Password changed.") }
            }.onFailure { error ->
                _state.update { it.copy(isBusy = false, error = error.message ?: "Failed to change password.") }
            }
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

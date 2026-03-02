package com.crispy.tv.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.crispy.tv.BuildConfig
import com.crispy.tv.accounts.ActiveProfileStore
import com.crispy.tv.accounts.SupabaseAccountClient
import com.crispy.tv.metadata.MetadataAddonRegistry
import com.crispy.tv.network.AppHttp
import com.crispy.tv.sync.HouseholdAddonsCloudSync
import com.crispy.tv.sync.ProfileDataCloudSync
import com.crispy.tv.ui.components.StandardTopAppBar
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccountsProfilesUiState(
    val configured: Boolean = false,
    val isBusy: Boolean = false,
    val statusMessage: String = "",
    val emailInput: String = "",
    val passwordInput: String = "",
    val authenticated: Boolean = false,
    val userId: String? = null,
    val email: String? = null,
    val householdId: String? = null,
    val householdRole: String? = null,
    val profiles: List<SupabaseAccountClient.Profile> = emptyList(),
    val activeProfileId: String? = null,
    val newProfileNameInput: String = "",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsProfilesRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }

    val viewModel: AccountsProfilesViewModel = viewModel(
        factory = remember(appContext) { AccountsProfilesViewModel.factory(appContext) }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val pageHorizontalPadding = responsivePageHorizontalPadding()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            StandardTopAppBar(
                title = "Accounts & Profiles",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = pageHorizontalPadding,
                end = pageHorizontalPadding,
                top = Dimensions.SectionSpacing,
                bottom = Dimensions.PageBottomPadding
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!uiState.configured) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(Dimensions.CardInternalPadding)) {
                            Text(
                                text = "Supabase is not configured.",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Set SUPABASE_URL and SUPABASE_ANON_KEY in your Gradle properties.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (uiState.statusMessage.isNotBlank()) {
                item {
                    Text(
                        text = uiState.statusMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                Text(
                    text = "Account",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!uiState.authenticated) {
                item {
                    OutlinedTextField(
                        value = uiState.emailInput,
                        onValueChange = viewModel::onEmailChanged,
                        label = { Text("Email") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = uiState.configured && !uiState.isBusy
                    )
                }
                item {
                    OutlinedTextField(
                        value = uiState.passwordInput,
                        onValueChange = viewModel::onPasswordChanged,
                        label = { Text("Password") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        enabled = uiState.configured && !uiState.isBusy
                    )
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = viewModel::signIn,
                            enabled = uiState.configured && !uiState.isBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Sign in")
                        }
                        OutlinedButton(
                            onClick = viewModel::signUp,
                            enabled = uiState.configured && !uiState.isBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Create")
                        }
                    }
                }
            } else {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(Dimensions.CardInternalPadding)) {
                            Text(
                                text = "Signed in",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = uiState.email ?: "(unknown email)",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (!uiState.userId.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "User: ${uiState.userId}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!uiState.householdId.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Household: ${uiState.householdId} (${uiState.householdRole ?: ""})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedButton(
                                    onClick = viewModel::refresh,
                                    enabled = uiState.configured && !uiState.isBusy,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Refresh")
                                }
                                Button(
                                    onClick = viewModel::signOut,
                                    enabled = uiState.configured && !uiState.isBusy,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Sign out")
                                }
                            }
                        }
                    }
                }
            }

            if (uiState.authenticated) {
                item {
                    Text(
                        text = "Profiles",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (uiState.profiles.isEmpty()) {
                    item {
                        Text(
                            text = "No profiles yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(items = uiState.profiles, key = { it.id }) { profile ->
                        ListItem(
                            headlineContent = { Text(profile.name) },
                            supportingContent = {
                                Text(
                                    text = "Order ${profile.orderIndex}",
                                    maxLines = 1
                                )
                            },
                            trailingContent = {
                                RadioButton(
                                    selected = profile.id == uiState.activeProfileId,
                                    onClick = { viewModel.selectProfile(profile.id) }
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.selectProfile(profile.id) }
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                }

                item {
                    OutlinedTextField(
                        value = uiState.newProfileNameInput,
                        onValueChange = viewModel::onNewProfileNameChanged,
                        label = { Text("New profile name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = uiState.configured && !uiState.isBusy
                    )
                }

                item {
                    Button(
                        onClick = viewModel::createProfile,
                        enabled = uiState.configured && !uiState.isBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Create profile")
                    }
                }
            }
        }
    }
}

class AccountsProfilesViewModel(
    private val supabase: SupabaseAccountClient,
    private val profileStore: ActiveProfileStore,
    private val profileDataCloudSync: ProfileDataCloudSync,
    private val householdAddonsCloudSync: HouseholdAddonsCloudSync,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AccountsProfilesUiState(configured = supabase.isConfigured()))
    val uiState: StateFlow<AccountsProfilesUiState> = _uiState

    init {
        refresh()
    }

    fun onEmailChanged(value: String) {
        _uiState.update { it.copy(emailInput = value) }
    }

    fun onPasswordChanged(value: String) {
        _uiState.update { it.copy(passwordInput = value) }
    }

    fun onNewProfileNameChanged(value: String) {
        _uiState.update { it.copy(newProfileNameInput = value) }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, statusMessage = "") }
            val session = runCatching { supabase.ensureValidSession() }.getOrNull()
            if (session == null) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        authenticated = false,
                        userId = null,
                        email = null,
                        householdId = null,
                        householdRole = null,
                        profiles = emptyList(),
                        activeProfileId = null
                    )
                }
                return@launch
            }

            val membership =
                runCatching { supabase.ensureHouseholdMembership(session.accessToken) }
                    .getOrElse {
                        _uiState.update { state ->
                            state.copy(
                                isBusy = false,
                                authenticated = true,
                                userId = session.userId,
                                email = session.email,
                                statusMessage = it.message.orEmpty(),
                            )
                        }
                        return@launch
                    }

            val profiles =
                runCatching { supabase.listProfiles(session.accessToken, membership.householdId) }
                    .getOrElse {
                        _uiState.update { state ->
                            state.copy(
                                isBusy = false,
                                authenticated = true,
                                userId = session.userId,
                                email = session.email,
                                householdId = membership.householdId,
                                householdRole = membership.role,
                                statusMessage = it.message.orEmpty(),
                            )
                        }
                        return@launch
                    }

            val storedActive = profileStore.getActiveProfileId(session.userId)
            val resolvedActive =
                storedActive?.takeIf { id -> profiles.any { it.id == id } }
                    ?: profiles.firstOrNull()?.id

            if (storedActive == null && resolvedActive != null) {
                profileStore.setActiveProfileId(session.userId, resolvedActive)
            }

            val settingsSyncError =
                profileDataCloudSync.pullForActiveProfile().exceptionOrNull()?.message
            val addonsSyncError =
                householdAddonsCloudSync.pullToLocal().exceptionOrNull()?.message

            _uiState.update {
                it.copy(
                    isBusy = false,
                    authenticated = true,
                    userId = session.userId,
                    email = session.email,
                    householdId = membership.householdId,
                    householdRole = membership.role,
                    profiles = profiles,
                    activeProfileId = resolvedActive,
                    statusMessage =
                        when {
                            !settingsSyncError.isNullOrBlank() -> "Settings sync failed: $settingsSyncError"
                            !addonsSyncError.isNullOrBlank() -> "Addons sync failed: $addonsSyncError"
                            else -> ""
                        }
                )
            }
        }
    }

    fun signIn() {
        viewModelScope.launch {
            val email = uiState.value.emailInput.trim()
            val password = uiState.value.passwordInput
            if (email.isBlank() || password.isBlank()) {
                _uiState.update { it.copy(statusMessage = "Enter email and password.") }
                return@launch
            }

            _uiState.update { it.copy(isBusy = true, statusMessage = "") }
            val result = runCatching { supabase.signInWithEmail(email, password) }
            result.onFailure {
                _uiState.update { state ->
                    state.copy(isBusy = false, statusMessage = it.message.orEmpty())
                }
                return@launch
            }

            _uiState.update { it.copy(passwordInput = "") }
            refresh()
        }
    }

    fun signUp() {
        viewModelScope.launch {
            val email = uiState.value.emailInput.trim()
            val password = uiState.value.passwordInput
            if (email.isBlank() || password.isBlank()) {
                _uiState.update { it.copy(statusMessage = "Enter email and password.") }
                return@launch
            }

            _uiState.update { it.copy(isBusy = true, statusMessage = "") }
            val result = runCatching { supabase.signUpWithEmail(email, password) }
            val signUp = result.getOrNull()
            if (signUp == null) {
                _uiState.update { state ->
                    state.copy(isBusy = false, statusMessage = result.exceptionOrNull()?.message.orEmpty())
                }
                return@launch
            }

            _uiState.update { it.copy(passwordInput = "", statusMessage = signUp.message, isBusy = false) }
            if (signUp.session != null) {
                refresh()
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            val userId = uiState.value.userId
            _uiState.update { it.copy(isBusy = true, statusMessage = "") }
            runCatching { supabase.signOut() }
            profileStore.clear(userId)
            refresh()
        }
    }

    fun selectProfile(profileId: String) {
        val userId = uiState.value.userId
        if (userId.isNullOrBlank()) return
        profileStore.setActiveProfileId(userId, profileId)
        _uiState.update { it.copy(activeProfileId = profileId, statusMessage = "") }

        viewModelScope.launch {
            profileDataCloudSync.pullForActiveProfile().onFailure {
                _uiState.update { s -> s.copy(statusMessage = "Settings sync failed: ${it.message.orEmpty()}") }
            }
        }
    }

    fun createProfile() {
        viewModelScope.launch {
            val state = uiState.value
            val householdId = state.householdId
            if (householdId.isNullOrBlank()) {
                _uiState.update { it.copy(statusMessage = "No household loaded.") }
                return@launch
            }

            val name = state.newProfileNameInput.trim()
            if (name.isBlank()) {
                _uiState.update { it.copy(statusMessage = "Enter a profile name.") }
                return@launch
            }
            if (name.length > 32) {
                _uiState.update { it.copy(statusMessage = "Profile name must be 32 characters or less.") }
                return@launch
            }

            val session = runCatching { supabase.ensureValidSession() }.getOrNull()
            if (session == null) {
                _uiState.update { it.copy(statusMessage = "Not signed in.") }
                return@launch
            }

            val nextOrderIndex = (state.profiles.maxOfOrNull { it.orderIndex } ?: 0) + 1

            _uiState.update { it.copy(isBusy = true, statusMessage = "") }
            val created =
                runCatching {
                    supabase.createProfile(
                        accessToken = session.accessToken,
                        householdId = householdId,
                        name = name,
                        orderIndex = nextOrderIndex,
                        createdByUserId = session.userId
                    )
                }

            created.onFailure {
                _uiState.update { s -> s.copy(isBusy = false, statusMessage = it.message.orEmpty()) }
                return@launch
            }

            _uiState.update { it.copy(newProfileNameInput = "", isBusy = false) }
            val newId = created.getOrNull()?.id
            if (!newId.isNullOrBlank() && !session.userId.isNullOrBlank()) {
                profileStore.setActiveProfileId(session.userId, newId)
            }
            refresh()
        }
    }

    companion object {
        fun factory(appContext: Context): ViewModelProvider.Factory {
            val safeContext = appContext.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val httpClient = AppHttp.client(safeContext)
                    val supabase =
                        SupabaseAccountClient(
                            appContext = safeContext,
                            httpClient = httpClient,
                            supabaseUrl = BuildConfig.SUPABASE_URL,
                            supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY
                        )
                    val profileStore = ActiveProfileStore(safeContext)
                    val profileDataCloudSync =
                        ProfileDataCloudSync(
                            context = safeContext,
                            supabase = supabase,
                            activeProfileStore = profileStore
                        )
                    val addonRegistry = MetadataAddonRegistry(safeContext, BuildConfig.METADATA_ADDON_URLS)
                    val householdAddonsCloudSync = HouseholdAddonsCloudSync(supabase, addonRegistry)

                    return AccountsProfilesViewModel(
                        supabase = supabase,
                        profileStore = profileStore,
                        profileDataCloudSync = profileDataCloudSync,
                        householdAddonsCloudSync = householdAddonsCloudSync
                    ) as T
                }
            }
        }
    }
}

package com.crispy.tv.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crispy.tv.accounts.ActiveProfileStore
import com.crispy.tv.accounts.SupabaseAccountClient
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.backend.BackendServicesProvider
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.ui.components.StandardTopAppBar
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import com.crispy.tv.ui.utils.appBarScrollBehavior
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

@Immutable
data class ProviderImportUiState(
    val connected: Boolean = false,
    val connectionStatus: String = "Disconnected",
    val externalUsername: String? = null,
    val latestJobStatus: String? = null,
    val latestJobError: String? = null,
)

@Immutable
data class ProviderPortalUiState(
    val configured: Boolean = false,
    val isBusy: Boolean = false,
    val statusMessage: String = "Run a backend import to populate watch history for the active profile.",
    val activeProfileName: String? = null,
    val activeProfileId: String? = null,
    val trakt: ProviderImportUiState = ProviderImportUiState(),
    val simkl: ProviderImportUiState = ProviderImportUiState(),
    val pendingExternalUrl: String? = null,
)

private data class ActiveProfileContext(
    val session: SupabaseAccountClient.Session,
    val profile: CrispyBackendClient.Profile,
)

internal class ProviderPortalViewModel(
    private val supabase: SupabaseAccountClient,
    private val backend: CrispyBackendClient,
    private val activeProfileStore: ActiveProfileStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        ProviderPortalUiState(
            configured = supabase.isConfigured() && backend.isConfigured(),
        )
    )
    val uiState: StateFlow<ProviderPortalUiState> = _uiState

    init {
        refreshImportState(forceMessage = null)
    }

    fun connectTrakt() {
        startImport(CrispyBackendClient.ImportProvider.TRAKT)
    }

    fun connectSimkl() {
        startImport(CrispyBackendClient.ImportProvider.SIMKL)
    }

    fun disconnectTrakt() {
        disconnectProvider(CrispyBackendClient.ImportProvider.TRAKT)
    }

    fun disconnectSimkl() {
        disconnectProvider(CrispyBackendClient.ImportProvider.SIMKL)
    }

    fun consumePendingExternalUrl() {
        _uiState.update { it.copy(pendingExternalUrl = null) }
    }

    fun onExternalLaunchFailed(message: String) {
        _uiState.update {
            it.copy(
                pendingExternalUrl = null,
                statusMessage = message,
            )
        }
    }

    fun refreshImportState(forceMessage: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, statusMessage = forceMessage ?: it.statusMessage) }

            val context = resolveActiveProfileContext()
            if (context == null) {
                return@launch
            }

            val connectionsResult = runCatching {
                backend.listImportConnections(context.session.accessToken, context.profile.id)
            }
            val jobsResult = runCatching {
                backend.listImportJobs(context.session.accessToken, context.profile.id)
            }

            val connections = connectionsResult.getOrNull()
            val jobs = jobsResult.getOrNull()
            val errorMessage =
                connectionsResult.exceptionOrNull()?.message
                    ?: jobsResult.exceptionOrNull()?.message

            _uiState.update {
                it.copy(
                    isBusy = false,
                    activeProfileId = context.profile.id,
                    activeProfileName = context.profile.name,
                    trakt = buildProviderState("trakt", connections?.providerAccounts, jobs?.jobs),
                    simkl = buildProviderState("simkl", connections?.providerAccounts, jobs?.jobs),
                    statusMessage =
                        forceMessage
                            ?: errorMessage
                            ?: "Imports run against ${context.profile.name}. Connect a provider to import watch history, playback, watchlist, and ratings.",
                )
            }
        }
    }

    private fun startImport(provider: CrispyBackendClient.ImportProvider) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, statusMessage = "") }
            val context = resolveActiveProfileContext() ?: return@launch
            val started =
                runCatching {
                    backend.startImport(
                        accessToken = context.session.accessToken,
                        profileId = context.profile.id,
                        provider = provider,
                    )
                }

            val result = started.getOrNull()
            if (result == null) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        statusMessage = started.exceptionOrNull()?.message.orEmpty(),
                    )
                }
                return@launch
            }

            val providerLabel = providerLabel(provider.apiValue)
            val message =
                when (result.nextAction) {
                    "authorize_provider" ->
                        "Continue in your browser to authorize $providerLabel. The backend will queue the import after approval."
                    else -> "$providerLabel import queued for ${context.profile.name}."
                }

            _uiState.update {
                it.copy(
                    isBusy = false,
                    pendingExternalUrl = result.authUrl,
                    statusMessage = message,
                )
            }
            refreshImportState(forceMessage = message)
        }
    }

    private fun disconnectProvider(provider: CrispyBackendClient.ImportProvider) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, statusMessage = "") }
            val context = resolveActiveProfileContext() ?: return@launch
            val providerLabel = providerLabel(provider.apiValue)
            val disconnected =
                runCatching {
                    backend.disconnectImportConnection(
                        accessToken = context.session.accessToken,
                        profileId = context.profile.id,
                        provider = provider,
                    )
                }

            val message =
                disconnected.fold(
                    onSuccess = { "$providerLabel disconnected from ${context.profile.name}." },
                    onFailure = { it.message ?: "Unable to disconnect $providerLabel." },
                )

            if (disconnected.isSuccess) {
                refreshImportState(forceMessage = message)
            } else {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        statusMessage = message,
                    )
                }
            }
        }
    }

    private suspend fun resolveActiveProfileContext(): ActiveProfileContext? {
        if (!supabase.isConfigured() || !backend.isConfigured()) {
            _uiState.update {
                it.copy(
                    isBusy = false,
                    configured = false,
                    statusMessage = "Set SUPABASE_URL, SUPABASE_PUBLISHABLE_KEY, and CRISPY_BACKEND_URL before using provider imports.",
                )
            }
            return null
        }

        val session = runCatching { supabase.ensureValidSession() }.getOrNull()
        if (session == null) {
            _uiState.update {
                it.copy(
                    isBusy = false,
                    activeProfileId = null,
                    activeProfileName = null,
                    statusMessage = "Sign in and select a profile before starting provider imports.",
                )
            }
            return null
        }

        val me = runCatching { backend.getMe(session.accessToken) }.getOrElse { error ->
            _uiState.update {
                it.copy(
                    isBusy = false,
                    statusMessage = error.message.orEmpty(),
                )
            }
            return null
        }

        val userKey = session.userId?.ifBlank { me.user.id } ?: me.user.id
        val storedProfileId = activeProfileStore.getActiveProfileId(userKey)
        val profile = me.profiles.firstOrNull { it.id == storedProfileId } ?: me.profiles.firstOrNull()
        if (profile == null) {
            _uiState.update {
                it.copy(
                    isBusy = false,
                    activeProfileId = null,
                    activeProfileName = null,
                    statusMessage = "Create and select a profile before starting provider imports.",
                )
            }
            return null
        }

        if (profile.id != storedProfileId) {
            activeProfileStore.setActiveProfileId(userKey, profile.id)
        }

        return ActiveProfileContext(
            session = session,
            profile = profile,
        )
    }

    private fun buildProviderState(
        provider: String,
        providerAccounts: List<CrispyBackendClient.ProviderAccount>?,
        jobs: List<CrispyBackendClient.ImportJob>?,
    ): ProviderImportUiState {
        val providerAccount = providerAccounts.orEmpty().firstOrNull { it.provider.equals(provider, ignoreCase = true) }
        val latestJob = jobs.orEmpty().firstOrNull { it.provider.equals(provider, ignoreCase = true) }
        return ProviderImportUiState(
            connected = providerAccount?.status.equals("connected", ignoreCase = true),
            connectionStatus = providerAccount?.status?.toDisplayLabel() ?: "Disconnected",
            externalUsername = providerAccount?.externalUsername,
            latestJobStatus = latestJob?.status?.toDisplayLabel(),
            latestJobError = latestJob?.errorMessage,
        )
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(ProviderPortalViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return ProviderPortalViewModel(
                            supabase = SupabaseServicesProvider.accountClient(appContext),
                            backend = BackendServicesProvider.backendClient(appContext),
                            activeProfileStore = SupabaseServicesProvider.activeProfileStore(appContext),
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }
}

@Composable
fun ProviderAuthPortalRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val viewModel: ProviderPortalViewModel =
        viewModel(
            factory = remember(appContext) {
                ProviderPortalViewModel.factory(appContext)
            }
        )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.pendingExternalUrl) {
        val launchUrl = uiState.pendingExternalUrl ?: return@LaunchedEffect
        val launchResult = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(launchUrl)))
        }
        if (launchResult.isFailure) {
            viewModel.onExternalLaunchFailed("Unable to open browser for provider authorization.")
        } else {
            viewModel.consumePendingExternalUrl()
        }
    }

    ProviderAuthPortalScreen(
        uiState = uiState,
        onConnectTrakt = viewModel::connectTrakt,
        onConnectSimkl = viewModel::connectSimkl,
        onDisconnectTrakt = viewModel::disconnectTrakt,
        onDisconnectSimkl = viewModel::disconnectSimkl,
        onRefresh = { viewModel.refreshImportState() },
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderAuthPortalScreen(
    uiState: ProviderPortalUiState,
    onConnectTrakt: () -> Unit,
    onConnectSimkl: () -> Unit,
    onDisconnectTrakt: () -> Unit,
    onDisconnectSimkl: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    val scrollBehavior = appBarScrollBehavior()
    val pageHorizontalPadding = responsivePageHorizontalPadding()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            StandardTopAppBar(
                title = "Provider Imports",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = uiState.configured && !uiState.isBusy) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Refresh",
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
                top = 12.dp,
                end = pageHorizontalPadding,
                bottom = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = uiState.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!uiState.activeProfileName.isNullOrBlank()) {
                item {
                    Text(
                        text = "Active profile: ${uiState.activeProfileName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            item {
                ProviderCard(
                    title = "Trakt",
                    state = uiState.trakt,
                    actionLabel = if (uiState.trakt.connected) "Run import" else "Connect & import",
                    actionEnabled = uiState.configured && !uiState.isBusy && !uiState.activeProfileId.isNullOrBlank(),
                    onAction = onConnectTrakt,
                    disconnectEnabled = uiState.configured && !uiState.isBusy && uiState.trakt.connected,
                    onDisconnect = onDisconnectTrakt,
                )
            }

            item {
                ProviderCard(
                    title = "Simkl",
                    state = uiState.simkl,
                    actionLabel = if (uiState.simkl.connected) "Run import" else "Connect & import",
                    actionEnabled = uiState.configured && !uiState.isBusy && !uiState.activeProfileId.isNullOrBlank(),
                    onAction = onConnectSimkl,
                    disconnectEnabled = uiState.configured && !uiState.isBusy && uiState.simkl.connected,
                    onDisconnect = onDisconnectSimkl,
                )
            }

            item {
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = uiState.configured && !uiState.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Refresh import status")
                }
            }
        }
    }
}

@Composable
private fun ProviderCard(
    title: String,
    state: ProviderImportUiState,
    actionLabel: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
    disconnectEnabled: Boolean,
    onDisconnect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = state.connectionStatus,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!state.externalUsername.isNullOrBlank()) {
                Text(
                    text = "Connected account: ${state.externalUsername}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!state.latestJobStatus.isNullOrBlank()) {
                Text(
                    text = "Latest import: ${state.latestJobStatus}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!state.latestJobError.isNullOrBlank()) {
                Text(
                    text = state.latestJobError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = onAction,
                enabled = actionEnabled,
            ) {
                Text(actionLabel)
            }
            if (state.connected) {
                OutlinedButton(
                    onClick = onDisconnect,
                    enabled = disconnectEnabled,
                ) {
                    Text("Disconnect")
                }
            }
        }
    }
}

private fun String.toDisplayLabel(): String {
    return trim()
        .replace('_', ' ')
        .lowercase(Locale.US)
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(separator = " ") { word ->
            word.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase(Locale.US) else char.toString()
            }
        }
        .ifBlank { this }
}

private fun providerLabel(provider: String): String {
    return when (provider.lowercase(Locale.US)) {
        "trakt" -> "Trakt"
        "simkl" -> "Simkl"
        else -> provider.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
    }
}

package com.crispy.rewrite.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crispy.rewrite.PlaybackLabDependencies
import com.crispy.rewrite.BuildConfig
import com.crispy.rewrite.player.WatchHistoryLabService
import com.crispy.rewrite.player.WatchProvider
import com.crispy.rewrite.player.WatchProviderAuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProviderPortalUiState(
    val traktRedirectUri: String = BuildConfig.TRAKT_REDIRECT_URI,
    val simklRedirectUri: String = BuildConfig.SIMKL_REDIRECT_URI,
    val statusMessage: String = "Connect providers to enable sync, continue watching, library, and comments.",
    val authState: WatchProviderAuthState = WatchProviderAuthState(),
    val pendingExternalUrl: String? = null
)

private data class ProviderPortalAction(
    val label: String,
    val onClick: () -> Unit
)

class ProviderPortalViewModel(
    private val watchHistoryService: WatchHistoryLabService
) : ViewModel() {
    private val _uiState = MutableStateFlow(ProviderPortalUiState())
    val uiState: StateFlow<ProviderPortalUiState> = _uiState

    init {
        viewModelScope.launch {
            // Listen for OAuth callback URIs published on the app-wide bus.
            // MainActivity performs the token exchange; the portal just refreshes UI state
            // so the connected status updates when a callback arrives.
            ProviderOAuthCallbackBus.callbacks.collect { callbackUri ->
                if (callbackUri.scheme != "crispy" || callbackUri.host != "auth") {
                    return@collect
                }
                // Clear any pending external URL and refresh the stored auth state so the
                // UI reflects any provider connection that may have just completed.
                _uiState.update {
                    it.copy(pendingExternalUrl = null, statusMessage = "Processing provider callback...")
                }
                refreshAuthState("Processing provider callback...")
            }
        }
        refreshAuthState()
    }

    fun connectTrakt() {
        viewModelScope.launch {
            val start = watchHistoryService.beginTraktOAuth()
            if (start == null) {
                _uiState.update {
                    it.copy(statusMessage = "Trakt OAuth is not configured. Set TRAKT_CLIENT_ID and TRAKT_REDIRECT_URI.")
                }
                return@launch
            }
            _uiState.update {
                it.copy(
                    statusMessage = start.statusMessage,
                    pendingExternalUrl = start.authorizationUrl
                )
            }
        }
    }

    fun disconnectTrakt() {
        watchHistoryService.disconnectProvider(WatchProvider.TRAKT)
        refreshAuthState("Disconnected Trakt.")
    }

    fun consumePendingExternalUrl() {
        _uiState.update { it.copy(pendingExternalUrl = null) }
    }

    fun onExternalLaunchFailed(message: String) {
        _uiState.update {
            it.copy(
                pendingExternalUrl = null,
                statusMessage = message
            )
        }
    }

    fun connectSimkl() {
        viewModelScope.launch {
            val start = watchHistoryService.beginSimklOAuth()
            if (start == null) {
                _uiState.update {
                    it.copy(statusMessage = "Simkl OAuth is not configured. Set SIMKL_CLIENT_ID and SIMKL_REDIRECT_URI.")
                }
                return@launch
            }
            _uiState.update {
                it.copy(
                    statusMessage = start.statusMessage,
                    pendingExternalUrl = start.authorizationUrl
                )
            }
        }
    }

    fun disconnectSimkl() {
        watchHistoryService.disconnectProvider(WatchProvider.SIMKL)
        refreshAuthState("Disconnected Simkl.")
    }

    fun refreshAuthState(message: String? = null) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    authState = watchHistoryService.authState(),
                    statusMessage = message ?: it.statusMessage
                )
            }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(ProviderPortalViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return ProviderPortalViewModel(
                            watchHistoryService = PlaybackLabDependencies.watchHistoryServiceFactory(appContext)
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
            viewModel.onExternalLaunchFailed("Unable to open browser for provider OAuth.")
        } else {
            viewModel.consumePendingExternalUrl()
        }
    }

    ProviderAuthPortalScreen(
        uiState = uiState,
        onConnectTrakt = viewModel::connectTrakt,
        onDisconnectTrakt = viewModel::disconnectTrakt,
        onConnectSimkl = viewModel::connectSimkl,
        onDisconnectSimkl = viewModel::disconnectSimkl,
        onRefresh = { viewModel.refreshAuthState() },
        onBack = onBack
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderAuthPortalScreen(
    uiState: ProviderPortalUiState,
    onConnectTrakt: () -> Unit,
    onDisconnectTrakt: () -> Unit,
    onConnectSimkl: () -> Unit,
    onDisconnectSimkl: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Provider Login Portal") }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = uiState.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                ProviderCard(
                    title = "Trakt",
                    connected = uiState.authState.traktAuthenticated,
                    userHandle = uiState.authState.traktSession?.userHandle,
                    expiresAtEpochMs = uiState.authState.traktSession?.expiresAtEpochMs,
                    tokenValue = null,
                    onTokenChanged = null,
                    connectAction = ProviderPortalAction("Connect Trakt OAuth", onConnectTrakt),
                    disconnectAction = ProviderPortalAction("Disconnect Trakt", onDisconnectTrakt)
                )
            }

            item {
                Text(
                    text = "Trakt redirect URI: ${uiState.traktRedirectUri}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                ProviderCard(
                    title = "Simkl",
                    connected = uiState.authState.simklAuthenticated,
                    userHandle = uiState.authState.simklSession?.userHandle,
                    expiresAtEpochMs = null,
                    tokenValue = null,
                    onTokenChanged = null,
                    connectAction = ProviderPortalAction("Connect Simkl OAuth", onConnectSimkl),
                    disconnectAction = ProviderPortalAction("Disconnect Simkl", onDisconnectSimkl)
                )
            }

            item {
                Text(
                    text = "Simkl redirect URI: ${uiState.simklRedirectUri}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                    Text("Refresh Status")
                }
            }
            item {
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("Back")
                }
            }
        }
    }
}

@Composable
private fun ProviderCard(
    title: String,
    connected: Boolean,
    userHandle: String?,
    expiresAtEpochMs: Long?,
    tokenValue: String?,
    onTokenChanged: ((String) -> Unit)?,
    connectAction: ProviderPortalAction,
    disconnectAction: ProviderPortalAction
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (connected) "Connected" else "Disconnected",
                style = MaterialTheme.typography.bodyMedium,
                color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!userHandle.isNullOrBlank()) {
                Text(
                    text = "User: $userHandle",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expiresAtEpochMs != null) {
                Text(
                    text = "Token expiry: $expiresAtEpochMs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (tokenValue != null && onTokenChanged != null) {
                OutlinedTextField(
                    value = tokenValue,
                    onValueChange = onTokenChanged,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("Access token") }
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = connectAction.onClick) {
                    Text(connectAction.label)
                }
                Button(onClick = disconnectAction.onClick) {
                    Text(disconnectAction.label)
                }
            }
        }
    }
}

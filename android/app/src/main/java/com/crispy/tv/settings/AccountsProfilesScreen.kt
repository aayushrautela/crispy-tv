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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.crispy.tv.accounts.AppLoginHandoff
import com.crispy.tv.accounts.SupabaseAccountClient
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.backend.BackendServicesProvider
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.ui.components.StandardTopAppBar
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import com.crispy.tv.ui.utils.appBarScrollBehavior
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class AccountsPortalUiState(
    val portalConfigured: Boolean = false,
    val isBusy: Boolean = false,
    val authenticated: Boolean = false,
    val userId: String? = null,
    val email: String? = null,
    val activeProfileName: String? = null,
    val statusMessage: String = "",
    val pendingPortalUrl: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsProfilesRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }

    val viewModel: AccountsPortalViewModel = viewModel(
        factory = remember(appContext) { AccountsPortalViewModel.factory(appContext) }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = appBarScrollBehavior()
    val pageHorizontalPadding = responsivePageHorizontalPadding()

    LaunchedEffect(uiState.pendingPortalUrl) {
        val urlStr = uiState.pendingPortalUrl
        if (urlStr != null) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(urlStr)))
            viewModel.clearPortalUrl()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            StandardTopAppBar(
                title = "Account",
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
            if (!uiState.portalConfigured) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(Dimensions.CardInternalPadding)) {
                            Text(
                                text = "Account portal is not configured.",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Set ACCOUNT_PORTAL_URL in your Gradle properties.",
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

            if (!uiState.authenticated) {
                item {
                    Button(
                        onClick = { viewModel.openSignIn() },
                        enabled = uiState.portalConfigured && !uiState.isBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Sign in with Crispy Account")
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
                            Text(
                                text = uiState.email ?: "(unknown)",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (!uiState.activeProfileName.isNullOrBlank()) {
                                Text(
                                    text = "Active profile: ${uiState.activeProfileName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { viewModel.openPortalPage("account") },
                                    enabled = uiState.portalConfigured && !uiState.isBusy,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Account settings")
                                }
                                Button(
                                    onClick = { viewModel.openPortalPage("profiles") },
                                    enabled = uiState.portalConfigured && !uiState.isBusy,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Manage profiles")
                                }
                                OutlinedButton(
                                    onClick = viewModel::signOut,
                                    enabled = !uiState.isBusy,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Sign out")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal class AccountsPortalViewModel(
    private val supabase: SupabaseAccountClient,
    private val backend: CrispyBackendClient,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        AccountsPortalUiState(
            portalConfigured = AppLoginHandoff.isPortalConfigured(),
        ),
    )
    val uiState: StateFlow<AccountsPortalUiState> = _uiState

    init {
        refresh()
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
                        activeProfileName = null,
                    )
                }
                return@launch
            }
            _uiState.update {
                it.copy(
                    isBusy = false,
                    authenticated = true,
                    userId = session.userId,
                    email = session.email,
                )
            }
            val me = runCatching { backend.getMe(session.accessToken) }.getOrNull()
            if (me != null) {
                _uiState.update {
                    it.copy(
                        activeProfileName = me.profiles.firstOrNull()?.name,
                        statusMessage = "",
                    )
                }
            }
        }
    }

    fun openSignIn() {
        val url = AppLoginHandoff.buildPortalLoginUrl(AppLoginHandoff.defaultReturnUri)
        if (url != null) {
            _uiState.update { it.copy(pendingPortalUrl = url.toString()) }
        }
    }

    fun openPortalPage(path: String) {
        val url = AppLoginHandoff.buildPortalPageUrl(path)
        if (url != null) {
            _uiState.update { it.copy(pendingPortalUrl = url.toString()) }
        }
    }

    fun clearPortalUrl() {
        _uiState.update { it.copy(pendingPortalUrl = null) }
    }

    fun signOut() {
        viewModelScope.launch {
            val userId = uiState.value.userId
            _uiState.update { it.copy(isBusy = true, statusMessage = "") }
            runCatching { supabase.signOut() }
            _uiState.update {
                it.copy(
                    isBusy = false,
                    authenticated = false,
                    userId = null,
                    email = null,
                    activeProfileName = null,
                    statusMessage = "Signed out.",
                )
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
                    val supabase = SupabaseServicesProvider.accountClient(safeContext)
                    val backend = BackendServicesProvider.backendClient(safeContext)
                    return AccountsPortalViewModel(
                        supabase = supabase,
                        backend = backend,
                    ) as T
                }
            }
        }
    }
}

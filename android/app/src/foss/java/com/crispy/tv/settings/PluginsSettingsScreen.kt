package com.crispy.tv.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crispy.tv.network.AppHttp
import com.crispy.tv.plugins.repo.PluginRepoClient
import com.crispy.tv.plugins.repo.PluginRepoInfo
import com.crispy.tv.ui.edge_to_edge.safeBottomPadding
import com.crispy.tv.ui.components.StandardTopAppBar
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import com.crispy.tv.ui.utils.appBarScrollBehavior
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
internal data class PluginsSettingsUiState(
    val repos: List<PluginRepoInfo> = emptyList(),
    val draftUrl: String = "",
    val isLoading: Boolean = true,
    val isInstalling: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

internal class PluginsSettingsViewModel(
    private val repoClient: PluginRepoClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PluginsSettingsUiState())
    val uiState: StateFlow<PluginsSettingsUiState> = _uiState

    init {
        reload(statusMessage = null)
    }

    fun setDraftUrl(value: String) {
        _uiState.update { state ->
            state.copy(draftUrl = value, errorMessage = null, statusMessage = null)
        }
    }

    fun install() {
        val draft = _uiState.value.draftUrl.trim()
        if (draft.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Enter a plugin repository URL first.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isInstalling = true, errorMessage = null, statusMessage = null) }
            val result = repoClient.install(draft)
            _uiState.update { state ->
                result.fold(
                    onSuccess = { scrapers ->
                        state.copy(
                            isInstalling = false,
                            draftUrl = "",
                            statusMessage = "Installed ${scrapers.size} scraper(s).",
                            repos = repoClient.repos(),
                        )
                    },
                    onFailure = { error ->
                        state.copy(isInstalling = false, errorMessage = error.message ?: "Install failed.")
                    },
                )
            }
        }
    }

    fun removeRepo(url: String) {
        viewModelScope.launch {
            repoClient.remove(url)
            reload(statusMessage = "Repository removed.")
        }
    }

    fun refreshRepo(url: String) {
        viewModelScope.launch {
            val result = repoClient.refresh(url)
            reload(
                statusMessage = if (result.isSuccess) "Repository refreshed." else null,
                errorMessage = result.exceptionOrNull()?.message,
            )
        }
    }

    fun toggleScraper(url: String, scraperId: String, enabled: Boolean) {
        viewModelScope.launch {
            val result = repoClient.setScraperEnabled(url, scraperId, enabled)
            if (result.isFailure) {
                _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message ?: "Update failed.") }
            }
            reload(statusMessage = null)
        }
    }

    private fun reload(statusMessage: String?, errorMessage: String? = null) {
        _uiState.update { state ->
            state.copy(
                repos = repoClient.repos(),
                isLoading = false,
                statusMessage = statusMessage ?: state.statusMessage,
                errorMessage = errorMessage,
            )
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(PluginsSettingsViewModel::class.java)) {
                        return PluginsSettingsViewModel(
                            repoClient = PluginRepoClient(appContext, AppHttp.okHttp(appContext)),
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsSettingsRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val viewModel: PluginsSettingsViewModel =
        viewModel(factory = remember(appContext) { PluginsSettingsViewModel.factory(appContext) })
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val scrollState = rememberScrollState()
    val scrollBehavior = appBarScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            StandardTopAppBar(
                title = "Plugins",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(scrollState)
                    .padding(horizontal = responsivePageHorizontalPadding())
                    .padding(bottom = safeBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SectionSpacing),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Install JavaScript plugin repositories to add more stream providers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(Dimensions.ListItemPadding)) {
                    OutlinedTextField(
                        value = uiState.draftUrl,
                        onValueChange = viewModel::setDraftUrl,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Repository URL") },
                        placeholder = { Text("https://example.com/plugins.json") },
                        isError = uiState.errorMessage != null,
                    )
                    uiState.errorMessage?.let { message ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    uiState.statusMessage?.let { message ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    FilledTonalButton(
                        onClick = viewModel::install,
                        enabled = !uiState.isInstalling,
                    ) {
                        if (uiState.isInstalling) {
                            CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.height(0.dp))
                        } else {
                            Text("Install repository")
                        }
                    }
                }
            }

            uiState.repos.forEach { repo ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(modifier = Modifier.padding(Dimensions.ListItemPadding)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = repo.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = repo.version?.let { version -> "${repo.url} • v$version" } ?: repo.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = { viewModel.refreshRepo(repo.url) }) {
                                Icon(imageVector = Icons.Outlined.Refresh, contentDescription = "Refresh repository")
                            }
                            IconButton(onClick = { viewModel.removeRepo(repo.url) }) {
                                Icon(imageVector = Icons.Outlined.Delete, contentDescription = "Remove repository")
                            }
                        }
                        repo.scrapers.forEach { scraper ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = scraper.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "v${scraper.version} • ${scraper.supportedTypes.joinToString()}" +
                                            if (scraper.enabled) "" else " • disabled",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Switch(
                                    checked = scraper.enabled,
                                    onCheckedChange = { checked ->
                                        viewModel.toggleScraper(repo.url, scraper.id, checked)
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (uiState.repos.isEmpty() && !uiState.isLoading) {
                TextButton(onClick = onBack) {
                    Text("Nothing here yet — install a repository to get started.")
                }
            }
        }
    }
}

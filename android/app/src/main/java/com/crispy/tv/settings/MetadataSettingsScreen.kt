package com.crispy.tv.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.sync.ProfileDataCloudSync
import com.crispy.tv.ui.components.StandardTopAppBar
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.utils.appBarScrollBehavior
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Composable
fun MetadataSettingsRoute(onBack: () -> Unit) {
    val appContext = LocalContext.current.applicationContext
    val vm: MetadataSettingsViewModel =
        viewModel(factory = remember(appContext) { MetadataSettingsViewModel.factory(appContext) })
    val uiState by vm.uiState.collectAsStateWithLifecycle()

    MetadataSettingsScreen(
        uiState = uiState,
        onBack = onBack,
        onOmdbKeyChanged = vm::setOmdbKey,
    )
}

@Immutable
data class MetadataSettingsUiState(
    val omdbKey: String = "",
)

private class MetadataSettingsViewModel(
    private val settingsStore: OmdbSettingsStore,
    private val cloudSync: ProfileDataCloudSync,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MetadataSettingsUiState(omdbKey = settingsStore.loadOmdbKey()))
    val uiState: StateFlow<MetadataSettingsUiState> = _uiState

    private var pushJob: Job? = null

    fun setOmdbKey(key: String) {
        settingsStore.saveOmdbKey(key)
        _uiState.update { it.copy(omdbKey = key) }

        pushJob?.cancel()
        pushJob =
            viewModelScope.launch {
                delay(800)
                cloudSync.pushForActiveAccount()
            }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MetadataSettingsViewModel(
                        settingsStore = OmdbSettingsStore(appContext),
                        cloudSync = SupabaseServicesProvider.createProfileDataCloudSync(appContext),
                    ) as T
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MetadataSettingsScreen(
    uiState: MetadataSettingsUiState,
    onBack: () -> Unit,
    onOmdbKeyChanged: (String) -> Unit,
) {
    val scrollBehavior = appBarScrollBehavior()
    var showKey by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            StandardTopAppBar(
                title = "Metadata",
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
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(Dimensions.ListItemPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Key,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "OMDb API",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Add your OMDb API key to show IMDb, Rotten Tomatoes, and Metacritic pills on details pages.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = uiState.omdbKey,
                        onValueChange = onOmdbKeyChanged,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("API Key") },
                        placeholder = { Text("e.g. a1b2c3d4") },
                        leadingIcon = {
                            Icon(imageVector = Icons.Outlined.Key, contentDescription = null)
                        },
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        supportingText = {
                            Text("Stored on-device and synced to your account when available. All profiles on the account share this key.")
                        },
                    )
                    FilterChip(
                        selected = showKey,
                        onClick = { showKey = !showKey },
                        label = { Text(if (showKey) "Hide key" else "Show key") },
                    )
                }
            }
        }
    }
}

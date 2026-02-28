package com.crispy.tv.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crispy.tv.ui.components.StandardTopAppBar
import com.crispy.tv.ui.theme.Dimensions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

@Composable
fun AiInsightsSettingsRoute(onBack: () -> Unit) {
    val appContext = LocalContext.current.applicationContext
    val vm: AiInsightsSettingsViewModel =
        viewModel(factory = remember(appContext) { AiInsightsSettingsViewModel.factory(appContext) })
    val uiState by vm.uiState.collectAsStateWithLifecycle()

    AiInsightsSettingsScreen(
        uiState = uiState,
        onBack = onBack,
        onModeSelected = vm::setMode,
        onModelTypeSelected = vm::setModelType,
        onCustomModelNameChanged = vm::setCustomModelName,
        onOpenRouterKeyChanged = vm::setOpenRouterKey,
    )
}

data class AiInsightsSettingsUiState(
    val snapshot: AiInsightsSettingsSnapshot = AiInsightsSettingsSnapshot(AiInsightsSettings(), ""),
)

private class AiInsightsSettingsViewModel(
    private val settingsStore: AiInsightsSettingsStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AiInsightsSettingsUiState(snapshot = settingsStore.loadSnapshot()))
    val uiState: StateFlow<AiInsightsSettingsUiState> = _uiState

    fun setMode(mode: AiInsightsMode) {
        updateSnapshot { it.copy(settings = it.settings.copy(mode = mode)) }
    }

    fun setModelType(type: AiInsightsModelType) {
        updateSnapshot { it.copy(settings = it.settings.copy(modelType = type)) }
    }

    fun setCustomModelName(name: String) {
        updateSnapshot { it.copy(settings = it.settings.copy(customModelName = name)) }
    }

    fun setOpenRouterKey(key: String) {
        updateSnapshot { it.copy(openRouterKey = key) }
    }

    private fun updateSnapshot(transform: (AiInsightsSettingsSnapshot) -> AiInsightsSettingsSnapshot) {
        val next = transform(_uiState.value.snapshot)
        settingsStore.saveSnapshot(next)
        _uiState.update { it.copy(snapshot = next) }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AiInsightsSettingsViewModel(
                        settingsStore = AiInsightsSettingsStore(appContext),
                    ) as T
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiInsightsSettingsScreen(
    uiState: AiInsightsSettingsUiState,
    onBack: () -> Unit,
    onModeSelected: (AiInsightsMode) -> Unit,
    onModelTypeSelected: (AiInsightsModelType) -> Unit,
    onCustomModelNameChanged: (String) -> Unit,
    onOpenRouterKeyChanged: (String) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    var showKey by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            StandardTopAppBar(
                title = "AI Insights",
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(Dimensions.ListItemPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = uiState.snapshot.settings.mode == AiInsightsMode.OFF,
                            onClick = { onModeSelected(AiInsightsMode.OFF) },
                            label = { Text("Off") },
                        )
                        FilterChip(
                            selected = uiState.snapshot.settings.mode == AiInsightsMode.ON_DEMAND,
                            onClick = { onModeSelected(AiInsightsMode.ON_DEMAND) },
                            label = { Text("On Demand") },
                        )
                        FilterChip(
                            selected = uiState.snapshot.settings.mode == AiInsightsMode.ALWAYS,
                            onClick = { onModeSelected(AiInsightsMode.ALWAYS) },
                            label = { Text("Always") },
                        )
                    }

                    Text(
                        text = "Uses TMDB reviews + metadata to generate a few fast, spoiler-light cards.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = uiState.snapshot.settings.modelType == AiInsightsModelType.DEEPSEEK_R1,
                            onClick = { onModelTypeSelected(AiInsightsModelType.DEEPSEEK_R1) },
                            label = { Text("DeepSeek") },
                            leadingIcon = {
                                Icon(imageVector = Icons.Outlined.Psychology, contentDescription = null)
                            }
                        )
                        FilterChip(
                            selected = uiState.snapshot.settings.modelType == AiInsightsModelType.NVIDIA_NEMOTRON,
                            onClick = { onModelTypeSelected(AiInsightsModelType.NVIDIA_NEMOTRON) },
                            label = { Text("Nemotron") },
                            leadingIcon = {
                                Icon(imageVector = Icons.Outlined.Psychology, contentDescription = null)
                            }
                        )
                        FilterChip(
                            selected = uiState.snapshot.settings.modelType == AiInsightsModelType.CUSTOM,
                            onClick = { onModelTypeSelected(AiInsightsModelType.CUSTOM) },
                            label = { Text("Custom") },
                            leadingIcon = {
                                Icon(imageVector = Icons.Outlined.Psychology, contentDescription = null)
                            }
                        )
                    }

                    if (uiState.snapshot.settings.modelType == AiInsightsModelType.CUSTOM) {
                        OutlinedTextField(
                            value = uiState.snapshot.settings.customModelName,
                            onValueChange = onCustomModelNameChanged,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("OpenRouter model") },
                            placeholder = { Text("e.g. anthropic/claude-3.7-sonnet") },
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = uiState.snapshot.openRouterKey,
                        onValueChange = onOpenRouterKeyChanged,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("OpenRouter API Key") },
                        leadingIcon = { Icon(imageVector = Icons.Outlined.Key, contentDescription = null) },
                        visualTransformation =
                            if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        supportingText = {
                            Text("Stored locally on this device.")
                        }
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilterChip(
                            selected = showKey,
                            onClick = { showKey = !showKey },
                            label = { Text(if (showKey) "Hide" else "Show") },
                        )
                    }
                }
            }

        }
    }
}

package com.crispy.rewrite.settings

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.crispy.rewrite.BuildConfig
import com.crispy.rewrite.metadata.CloudAddonRow
import com.crispy.rewrite.metadata.MetadataAddonRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

internal data class InstalledAddonUi(
    val installationId: String,
    val manifestUrl: String,
    val addonId: String,
    val name: String,
    val description: String,
    val logoUrl: String?,
    val version: String?,
    val resources: List<String>,
    val types: List<String>
)

internal data class PendingAddonInstallUi(
    val manifestUrl: String,
    val name: String,
    val description: String,
    val addonId: String?,
    val version: String?,
    val logoUrl: String?,
    val resources: List<String>,
    val types: List<String>,
    val warnings: List<String>,
    val manifestJson: String
)

internal data class AddonsSettingsUiState(
    val installedAddons: List<InstalledAddonUi> = emptyList(),
    val draftUrl: String = "",
    val pendingInstall: PendingAddonInstallUi? = null,
    val isLoading: Boolean = true,
    val isCheckingAddon: Boolean = false,
    val isInstallingAddon: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

internal class AddonsSettingsViewModel(
    private val addonRegistry: MetadataAddonRegistry
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddonsSettingsUiState())
    val uiState: StateFlow<AddonsSettingsUiState> = _uiState

    init {
        refreshInstalledAddons()
    }

    fun setDraftUrl(value: String) {
        _uiState.update { state ->
            state.copy(
                draftUrl = value,
                errorMessage = null,
                statusMessage = null
            )
        }
    }

    fun prepareInstall() {
        val draft = _uiState.value.draftUrl.trim()
        if (draft.isEmpty()) {
            _uiState.update { state ->
                state.copy(errorMessage = "Enter an addon manifest URL first.")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    isCheckingAddon = true,
                    pendingInstall = null,
                    errorMessage = null,
                    statusMessage = null
                )
            }

            val manifestUrl = normalizeManifestUrl(draft)
            if (manifestUrl == null) {
                _uiState.update { state ->
                    state.copy(
                        isCheckingAddon = false,
                        errorMessage = "The addon URL is invalid. Try a full manifest URL."
                    )
                }
                return@launch
            }

            val alreadyInstalled =
                addonRegistry
                    .exportCloudAddons()
                    .any { row -> manifestUrlsMatch(row.manifestUrl, manifestUrl) }
            if (alreadyInstalled) {
                _uiState.update { state ->
                    state.copy(
                        isCheckingAddon = false,
                        errorMessage = "That addon is already installed."
                    )
                }
                return@launch
            }

            val manifest =
                withContext(Dispatchers.IO) {
                    httpGetJson(manifestUrl)
                }
            if (manifest == null) {
                _uiState.update { state ->
                    state.copy(
                        isCheckingAddon = false,
                        errorMessage = "Unable to load addon manifest from that URL."
                    )
                }
                return@launch
            }

            val preview = buildPendingInstall(manifestUrl = manifestUrl, manifest = manifest)
            _uiState.update { state ->
                state.copy(
                    isCheckingAddon = false,
                    pendingInstall = preview,
                    errorMessage = null
                )
            }
        }
    }

    fun dismissPendingInstall() {
        _uiState.update { state ->
            state.copy(pendingInstall = null)
        }
    }

    fun confirmInstall() {
        val pending = _uiState.value.pendingInstall ?: return

        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    isInstallingAddon = true,
                    errorMessage = null,
                    statusMessage = null
                )
            }

            val rowsByUrl = linkedMapOf<String, String>()
            addonRegistry
                .exportCloudAddons()
                .sortedBy { row -> row.sortOrder }
                .forEach { row ->
                    rowsByUrl.putIfAbsent(row.manifestUrl.lowercase(Locale.US), row.manifestUrl)
                }
            rowsByUrl[pending.manifestUrl.lowercase(Locale.US)] = pending.manifestUrl

            val rows =
                rowsByUrl.values.mapIndexed { index, manifestUrl ->
                    CloudAddonRow(
                        manifestUrl = manifestUrl,
                        sortOrder = index
                    )
                }

            val installedCount = addonRegistry.reconcileCloudAddons(rows)
            if (installedCount == 0) {
                _uiState.update { state ->
                    state.copy(
                        isInstallingAddon = false,
                        errorMessage = "Could not install addon from that manifest URL."
                    )
                }
                return@launch
            }

            runCatching {
                JSONObject(pending.manifestJson)
            }.getOrNull()?.let { manifest ->
                addonRegistry
                    .orderedSeeds()
                    .firstOrNull { seed -> manifestUrlsMatch(seed.manifestUrl, pending.manifestUrl) }
                    ?.let { seed -> addonRegistry.cacheManifest(seed, manifest) }
            }

            val installedAddons = loadInstalledAddons()
            _uiState.update { state ->
                state.copy(
                    installedAddons = installedAddons,
                    draftUrl = "",
                    pendingInstall = null,
                    isLoading = false,
                    isInstallingAddon = false,
                    statusMessage = "Installed ${pending.name}.",
                    errorMessage = null
                )
            }
        }
    }

    fun removeAddon(addon: InstalledAddonUi) {
        val targetId = addon.addonId.ifBlank { addonIdFromUrl(addon.manifestUrl) }
        if (targetId.isBlank()) {
            _uiState.update { state ->
                state.copy(errorMessage = "Could not resolve addon id for removal.")
            }
            return
        }

        viewModelScope.launch {
            addonRegistry.markAddonRemoved(targetId)
            val installedAddons = loadInstalledAddons()
            _uiState.update { state ->
                state.copy(
                    installedAddons = installedAddons,
                    isLoading = false,
                    statusMessage = "Removed ${addon.name}.",
                    errorMessage = null
                )
            }
        }
    }

    private fun refreshInstalledAddons() {
        val installedAddons = loadInstalledAddons()
        _uiState.update { state ->
            state.copy(
                installedAddons = installedAddons,
                isLoading = false
            )
        }
    }

    private fun loadInstalledAddons(): List<InstalledAddonUi> {
        return addonRegistry.orderedSeeds().map { seed ->
            val manifest = parseCachedManifest(seed.cachedManifestJson)
            val manifestName = nonBlank(manifest?.optString("name"))
            val addonId = nonBlank(manifest?.optString("id")) ?: seed.addonIdHint
            val version = nonBlank(manifest?.optString("version"))
            val description =
                nonBlank(manifest?.optString("description"))
                    ?: seed.manifestUrl

            InstalledAddonUi(
                installationId = seed.installationId,
                manifestUrl = seed.manifestUrl,
                addonId = addonId,
                name = manifestName ?: addonId,
                description = description,
                logoUrl =
                    resolveAddonAssetUrl(
                        baseUrl = seed.baseUrl,
                        rawAssetUrl = nonBlank(manifest?.optString("logo"))
                    ),
                version = version,
                resources = parseManifestResources(manifest),
                types = parseStringArray(manifest?.optJSONArray("types"))
            )
        }
    }

    private fun buildPendingInstall(
        manifestUrl: String,
        manifest: JSONObject
    ): PendingAddonInstallUi {
        val baseUrl = addonBaseUrl(manifestUrl)
        val addonId = nonBlank(manifest.optString("id"))
        val name = nonBlank(manifest.optString("name")) ?: addonId ?: "Unknown addon"
        val description = nonBlank(manifest.optString("description")) ?: manifestUrl
        val resources = parseManifestResources(manifest)
        val types = parseStringArray(manifest.optJSONArray("types"))

        val warnings = mutableListOf<String>()
        if (manifestUrl.startsWith("http://", ignoreCase = true)) {
            warnings += "This addon uses an insecure HTTP URL."
        }
        if (addonId.isNullOrBlank()) {
            warnings += "Manifest is missing a stable addon id."
        }
        if (resources.isEmpty()) {
            warnings += "Manifest does not list addon resources."
        }
        if (types.isEmpty()) {
            warnings += "Manifest does not declare supported media types."
        }

        return PendingAddonInstallUi(
            manifestUrl = manifestUrl,
            name = name,
            description = description,
            addonId = addonId,
            version = nonBlank(manifest.optString("version")),
            logoUrl =
                resolveAddonAssetUrl(
                    baseUrl = baseUrl,
                    rawAssetUrl = nonBlank(manifest.optString("logo"))
                ),
            resources = resources,
            types = types,
            warnings = warnings,
            manifestJson = manifest.toString()
        )
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(AddonsSettingsViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return AddonsSettingsViewModel(
                            addonRegistry =
                                MetadataAddonRegistry(
                                    context = appContext,
                                    configuredManifestUrlsCsv = BuildConfig.METADATA_ADDON_URLS
                                )
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }
}

@Composable
fun AddonsSettingsRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val viewModel: AddonsSettingsViewModel =
        viewModel(
            factory = remember(appContext) {
                AddonsSettingsViewModel.factory(appContext)
            }
        )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    AddonsSettingsScreen(
        uiState = uiState,
        onBack = onBack,
        onDraftChange = viewModel::setDraftUrl,
        onPrepareInstall = viewModel::prepareInstall,
        onDismissInstall = viewModel::dismissPendingInstall,
        onConfirmInstall = viewModel::confirmInstall,
        onRemoveAddon = viewModel::removeAddon
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddonsSettingsScreen(
    uiState: AddonsSettingsUiState,
    onBack: () -> Unit,
    onDraftChange: (String) -> Unit,
    onPrepareInstall: () -> Unit,
    onDismissInstall: () -> Unit,
    onConfirmInstall: () -> Unit,
    onRemoveAddon: (InstalledAddonUi) -> Unit
) {
    val scrollState = rememberScrollState()
    var pendingRemoval by remember { mutableStateOf<InstalledAddonUi?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Addons",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            AddonsSection(title = "ADD NEW ADDON") {
                Text(
                    text = "Install addon manifests to add new streams and catalogs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
                OutlinedTextField(
                    value = uiState.draftUrl,
                    onValueChange = onDraftChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    singleLine = true,
                    label = { Text("Manifest URL") },
                    placeholder = { Text("https://example.com/manifest.json") }
                )
                Spacer(modifier = Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = onPrepareInstall,
                    enabled = !uiState.isCheckingAddon && !uiState.isInstallingAddon,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(if (uiState.isCheckingAddon) "Checking..." else "Install Addon")
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            AddonsSection(title = "INSTALLED ADDONS") {
                when {
                    uiState.isLoading -> {
                        Text(
                            text = "Loading installed addons...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                        )
                    }

                    uiState.installedAddons.isEmpty() -> {
                        Text(
                            text = "No addons installed.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                        )
                    }

                    else -> {
                        uiState.installedAddons.forEachIndexed { index, addon ->
                            AddonListRow(
                                addon = addon,
                                onRemove = { pendingRemoval = addon }
                            )
                            if (index < uiState.installedAddons.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 68.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                        }
                    }
                }
            }

            uiState.errorMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }

            uiState.statusMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    uiState.pendingInstall?.let { pending ->
        AlertDialog(
            onDismissRequest = {
                if (!uiState.isInstallingAddon) {
                    onDismissInstall()
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onConfirmInstall,
                    enabled = !uiState.isInstallingAddon
                ) {
                    Text(if (uiState.isInstallingAddon) "Installing..." else "Install")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismissInstall,
                    enabled = !uiState.isInstallingAddon
                ) {
                    Text("Cancel")
                }
            },
            title = { Text("Confirm Addon Install") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = pending.name,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = pending.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "URL: ${pending.manifestUrl}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    pending.addonId?.let { addonId ->
                        Text(
                            text = "ID: $addonId",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    pending.version?.let { version ->
                        Text(
                            text = "Version: $version",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        text = "Resources: ${pending.resources.joinToString().ifBlank { "none" }}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "Types: ${pending.types.joinToString().ifBlank { "none" }}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (pending.warnings.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Warnings",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        pending.warnings.forEach { warning ->
                            Text(
                                text = "- $warning",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        )
    }

    pendingRemoval?.let { addon ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveAddon(addon)
                        pendingRemoval = null
                    }
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) {
                    Text("Cancel")
                }
            },
            title = { Text("Remove Addon") },
            text = { Text("Remove ${addon.name} from installed addons?") }
        )
    }
}

@Composable
private fun AddonsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                content = content
            )
        }
    }
}

@Composable
private fun AddonListRow(
    addon: InstalledAddonUi,
    onRemove: () -> Unit
) {
    ListItem(
        leadingContent = {
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                if (addon.logoUrl != null) {
                    AsyncImage(
                        model = addon.logoUrl,
                        contentDescription = null,
                        modifier =
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Extension,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        },
        headlineContent = {
            Text(
                text = addon.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            val details =
                buildString {
                    append(addon.addonId)
                    addon.version?.let { version ->
                        append(" - v")
                        append(version)
                    }
                }
            Text(
                text = details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailingContent = {
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "Remove addon",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    )
}

private fun parseCachedManifest(raw: String?): JSONObject? {
    val payload = raw?.trim().orEmpty()
    if (payload.isEmpty()) {
        return null
    }
    return runCatching { JSONObject(payload) }.getOrNull()
}

private fun parseStringArray(array: JSONArray?): List<String> {
    if (array == null) {
        return emptyList()
    }
    val values = mutableListOf<String>()
    for (index in 0 until array.length()) {
        val value = array.optString(index).trim()
        if (value.isNotEmpty()) {
            values += value
        }
    }
    return values
}

private fun parseManifestResources(manifest: JSONObject?): List<String> {
    val resourcesArray = manifest?.optJSONArray("resources") ?: return emptyList()
    val values = linkedSetOf<String>()
    for (index in 0 until resourcesArray.length()) {
        when (val entry = resourcesArray.opt(index)) {
            is String -> {
                val normalized = entry.trim()
                if (normalized.isNotEmpty()) {
                    values += normalized
                }
            }

            is JSONObject -> {
                val name = entry.optString("name").trim()
                if (name.isNotEmpty()) {
                    values += name
                }
            }
        }
    }
    return values.toList()
}

private fun addonIdFromUrl(manifestUrl: String): String {
    val uri = Uri.parse(manifestUrl)
    return uri.host?.trim().orEmpty()
}

private fun normalizeManifestUrl(raw: String): String? {
    val input = raw.trim()
    if (input.isEmpty()) {
        return null
    }

    val normalizedInput =
        when {
            input.startsWith("stremio://", ignoreCase = true) -> "https://${input.substringAfter("://")}"
            URI_SCHEME_REGEX.containsMatchIn(input) -> input
            else -> "https://$input"
        }

    val parsedUri = Uri.parse(normalizedInput)
    val uri =
        when (parsedUri.scheme?.lowercase(Locale.US)) {
            null, "", "stremio" -> parsedUri.buildUpon().scheme("https").build()
            else -> parsedUri
        }

    val host = uri.host?.takeIf { value -> value.isNotBlank() } ?: return null
    val pathSegments = uri.pathSegments.filter { segment -> segment.isNotBlank() }.toMutableList()
    if (!pathSegments.lastOrNull().equals("manifest.json", ignoreCase = true)) {
        pathSegments += "manifest.json"
    }

    val builder =
        Uri.Builder()
            .scheme(uri.scheme ?: "https")
            .encodedAuthority(uri.encodedAuthority ?: host)
    pathSegments.forEach { segment -> builder.appendPath(segment) }
    uri.encodedQuery?.let { encodedQuery ->
        if (encodedQuery.isNotBlank()) {
            builder.encodedQuery(encodedQuery)
        }
    }

    return builder.build().toString()
}

private fun addonBaseUrl(manifestUrl: String): String {
    val uri = Uri.parse(manifestUrl)
    val pathSegments = uri.pathSegments.filter { segment -> segment.isNotBlank() }
    val baseSegments =
        if (pathSegments.lastOrNull().equals("manifest.json", ignoreCase = true)) {
            pathSegments.dropLast(1)
        } else {
            pathSegments
        }

    val builder =
        Uri.Builder()
            .scheme(uri.scheme ?: "https")
            .encodedAuthority(uri.encodedAuthority)
    baseSegments.forEach { segment -> builder.appendPath(segment) }
    return builder.build().toString().trimEnd('/')
}

private fun resolveAddonAssetUrl(baseUrl: String, rawAssetUrl: String?): String? {
    val assetUrl = rawAssetUrl?.trim().orEmpty()
    if (assetUrl.isEmpty()) {
        return null
    }
    val assetUri = Uri.parse(assetUrl)
    if (assetUri.scheme != null) {
        return assetUrl
    }

    val baseUri = Uri.parse(baseUrl)
    return when {
        assetUrl.startsWith("//") -> "${baseUri.scheme ?: "https"}:$assetUrl"
        assetUrl.startsWith("/") -> {
            Uri.Builder()
                .scheme(baseUri.scheme ?: "https")
                .encodedAuthority(baseUri.encodedAuthority)
                .encodedPath(assetUrl)
                .build()
                .toString()
        }

        else -> {
            val normalizedBase = baseUrl.trimEnd('/')
            "$normalizedBase/$assetUrl"
        }
    }
}

private fun manifestUrlsMatch(left: String, right: String): Boolean {
    return left.trim().equals(right.trim(), ignoreCase = true)
}

private fun httpGetJson(url: String): JSONObject? {
    return runCatching {
        val connection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 12000
                setRequestProperty("Accept", "application/json")
            }

        try {
            connection.inputStream.bufferedReader().use { reader ->
                val payload = reader.readText()
                if (payload.isBlank()) {
                    null
                } else {
                    JSONObject(payload)
                }
            }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

private fun nonBlank(value: String?): String? {
    val trimmed = value?.trim()
    return if (trimmed.isNullOrEmpty()) null else trimmed
}

private val URI_SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

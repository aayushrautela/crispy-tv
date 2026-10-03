package com.crispy.tv.settings

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.addons.registry.ManifestUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.crispy.tv.addons.registry.CloudAddonRow
import com.crispy.tv.addons.registry.MetadataAddonRegistry
import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.sync.HouseholdAddonsCloudSync
import com.crispy.tv.ui.components.CrispyIcon
import com.crispy.tv.ui.components.StandardTopAppBar
import com.crispy.tv.ui.edge_to_edge.safeBottomPadding
import com.crispy.tv.ui.resources.Res
import com.crispy.tv.ui.resources.ic_add
import com.crispy.tv.ui.resources.ic_arrow_back
import com.crispy.tv.ui.resources.ic_delete
import com.crispy.tv.ui.resources.ic_extension
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import com.crispy.tv.ui.utils.appBarScrollBehavior
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import com.crispy.tv.library.optJsonArray
import com.crispy.tv.library.optStringOrEmpty
import com.crispy.tv.library.stringAtOrEmpty
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

@Immutable
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

@Immutable
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

@Immutable
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
    private val addonRegistry: MetadataAddonRegistry,
    private val httpClient: CrispyHttpClient,
    private val householdAddonsCloudSync: HouseholdAddonsCloudSync,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddonsSettingsUiState())
    val uiState: StateFlow<AddonsSettingsUiState> = _uiState

    init {
        refreshInstalledAddons()

        viewModelScope.launch {
            householdAddonsCloudSync.pullToLocal()
                .onSuccess { refreshInstalledAddons() }
                .onFailure {
                    _uiState.update { state ->
                        state.copy(errorMessage = "Addons sync failed: ${it.message.orEmpty()}")
                    }
                }
        }
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
                withContext(ioDispatcher) {
                    httpGetJson(httpClient, manifestUrl)
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
                    // `putIfAbsent` spelled out, because the stdlib extension is declared
                    // only for the JVM and so does not resolve in `commonMain`. It is a pin
                    // with no import: it needs no import to write, no import scan can see
                    // it, and `check_common_purity.py` has nothing to match. Only the
                    // metadata compilation finds one, which is why that task is the gate
                    // before moving any file into `commonMain`. The behaviour is identical
                    // -- keep the first spelling seen for a lowercased url.
                    val key = row.manifestUrl.lowercase()
                    if (!rowsByUrl.containsKey(key)) {
                        rowsByUrl[key] = row.manifestUrl
                    }
                }
            rowsByUrl[pending.manifestUrl.lowercase()] = pending.manifestUrl

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
                // `Json.parseToJsonElement` raises `SerializationException` where
                // `JSONObject(String)` raised `JSONException`; the `runCatching`
                // absorbs either, and the census measured **zero**
                // `JSONException` occurrences in `:app`, so nothing could have
                // depended on the type.
                Json.parseToJsonElement(pending.manifestJson).jsonObject
            }.getOrNull()?.let { manifest ->
                addonRegistry
                    .orderedSeeds()
                    .firstOrNull { seed -> manifestUrlsMatch(seed.manifestUrl, pending.manifestUrl) }
                    ?.let { seed -> addonRegistry.cacheManifest(seed, manifest) }
            }

            val installedAddons = loadInstalledAddons()
            val syncError = householdAddonsCloudSync.pushFromLocal().exceptionOrNull()?.message
            _uiState.update { state ->
                state.copy(
                    installedAddons = installedAddons,
                    draftUrl = "",
                    pendingInstall = null,
                    isLoading = false,
                    isInstallingAddon = false,
                    statusMessage =
                        if (syncError.isNullOrBlank()) {
                            "Installed ${pending.name}."
                        } else {
                            "Installed ${pending.name} (sync failed)."
                        },
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
            val syncError = householdAddonsCloudSync.pushFromLocal().exceptionOrNull()?.message
            _uiState.update { state ->
                state.copy(
                    installedAddons = installedAddons,
                    isLoading = false,
                    statusMessage =
                        if (syncError.isNullOrBlank()) {
                            "Removed ${addon.name}."
                        } else {
                            "Removed ${addon.name} (sync failed)."
                        },
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
            val manifestName = nonBlank(manifest?.optStringOrEmpty("name"))
            val addonId = nonBlank(manifest?.optStringOrEmpty("id")) ?: seed.addonIdHint
            val version = nonBlank(manifest?.optStringOrEmpty("version"))
            val description =
                nonBlank(manifest?.optStringOrEmpty("description"))
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
                        rawAssetUrl = nonBlank(manifest?.optStringOrEmpty("logo"))
                    ),
                version = version,
                resources = parseManifestResources(manifest),
                types = parseStringArray(manifest?.optJsonArray("types"))
            )
        }
    }

    private fun buildPendingInstall(
        manifestUrl: String,
        manifest: JsonObject
    ): PendingAddonInstallUi {
        val baseUrl = addonBaseUrl(manifestUrl)
        val addonId = nonBlank(manifest.optStringOrEmpty("id"))
        val name = nonBlank(manifest.optStringOrEmpty("name")) ?: addonId ?: "Unknown addon"
        val description = nonBlank(manifest.optStringOrEmpty("description")) ?: manifestUrl
        val resources = parseManifestResources(manifest)
        val types = parseStringArray(manifest.optJsonArray("types"))

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
            version = nonBlank(manifest.optStringOrEmpty("version")),
            logoUrl =
                resolveAddonAssetUrl(
                    baseUrl = baseUrl,
                    rawAssetUrl = nonBlank(manifest.optStringOrEmpty("logo"))
                ),
            resources = resources,
            types = types,
            warnings = warnings,
            manifestJson = manifest.toString()
        )
    }
}

@Composable
fun AddonsSettingsRoute(
    onBack: () -> Unit,
    viewModelFactory: ViewModelProvider.Factory,
) {
    val viewModel: AddonsSettingsViewModel = viewModel(factory = viewModelFactory)
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

    val pageHorizontalPadding = responsivePageHorizontalPadding()
    val scrollBehavior = appBarScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            StandardTopAppBar(
                title = "Addons",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        CrispyIcon(
                            painter = painterResource(Res.drawable.ic_arrow_back),
                            contentDescription = "Back",
                            autoMirror = true,
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(scrollState)
                    .padding(bottom = safeBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SectionSpacing)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            AddonsSection(title = "ADD NEW ADDON") {
                Text(
                    text = "Install addon manifests to add new streams and catalogs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Dimensions.ListItemPadding, vertical = 12.dp)
                )
                OutlinedTextField(
                    value = uiState.draftUrl,
                    onValueChange = onDraftChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimensions.ListItemPadding),
                    singleLine = true,
                    label = { Text("Manifest URL") },
                    placeholder = { Text("https://example.com/manifest.json") }
                )
                Spacer(modifier = Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = onPrepareInstall,
                    enabled = !uiState.isCheckingAddon && !uiState.isInstallingAddon,
                    modifier = Modifier.padding(horizontal = Dimensions.ListItemPadding)
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_add),
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
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
                        painter = painterResource(Res.drawable.ic_extension),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
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
                    painter = painterResource(Res.drawable.ic_delete),
                    contentDescription = "Remove addon",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    ) {
        Text(
            text = addon.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun parseCachedManifest(raw: String?): JsonObject? {
    val payload = raw?.trim().orEmpty()
    if (payload.isEmpty()) {
        return null
    }
    return runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull()
}

private fun parseStringArray(array: JsonArray?): List<String> {
    if (array == null) {
        return emptyList()
    }
    val values = mutableListOf<String>()
    for (index in 0 until array.size) {
        val value = array.stringAtOrEmpty(index).trim()
        if (value.isNotEmpty()) {
            values += value
        }
    }
    return values
}

private fun parseManifestResources(manifest: JsonObject?): List<String> {
    val resourcesArray = manifest?.optJsonArray("resources") ?: return emptyList()
    val values = linkedSetOf<String>()
    for (entry in resourcesArray) {
        when (entry) {
            // **`is JsonPrimitive` is not `is String`, and the difference is this
            // arm's whole behaviour.** A JSON *number* is a `JsonPrimitive` too,
            // so widening the arm would start admitting values the old code
            // dropped -- and `contentOrNull` answers the same string for the
            // number `1234` and the quoted string `"1234"`, so **`isString` is
            // the only thing that can tell them apart.** That is the same reason
            // `:backend`'s `optLongOrDefault` branches on it.
            is JsonPrimitive -> {
                val normalized = if (entry.isString) entry.contentOrNull.orEmpty().trim() else ""
                if (normalized.isNotEmpty()) {
                    values += normalized
                }
            }

            is JsonObject -> {
                val name = entry.optStringOrEmpty("name").trim()
                if (name.isNotEmpty()) {
                    values += name
                }
            }

            // Sealed-type fall-through: a JSON array element matched neither arm
            // under `org.json` and was dropped without a word.
            else -> Unit
        }
    }
    return values.toList()
}

/**
 * The add-on's own id, read off its manifest url.
 *
 * The old answer was `Uri.parse(url).host?.trim().orEmpty()`, and `Uri.parse` never
 * returns null and treats a bare word as a host -- so `"foo"` answered `"foo"`. This
 * asks `ManifestUri.parse`, which requires `://` and a non-blank host, so a row whose
 * manifest url is broken now reports an *unknown* id rather than adopting the whole
 * string as its id. That is the better answer for this caller specifically: the string
 * is not an id, and an id is what it is being asked for.
 */
internal fun addonIdFromUrl(manifestUrl: String): String {
    return ManifestUri.parse(manifestUrl)?.host?.trim().orEmpty()
}

/**
 * The segments a manifest is expected to sit at: the ones the url already has, plus
 * `manifest.json` unless the last already names it.
 *
 * This was inline in [normalizeManifestUrl], where it was unreachable by any test
 * because the only way in was through a whole url. It is named so `commonTest` can ask
 * what happens to a url that *already* ends in the manifest -- the case a user hits by
 * pasting the manifest url they were handed, which must not gain a second
 * `manifest.json`.
 */
internal fun manifestSegments(pathSegments: List<String>): List<String> =
    if (pathSegments.lastOrNull().equals(MANIFEST_SEGMENT, ignoreCase = true)) {
        pathSegments
    } else {
        pathSegments + MANIFEST_SEGMENT
    }

/**
 * The base a relative asset url resolves against: the segments with a trailing
 * `manifest.json` removed, so the result is the add-on's *directory* rather than the
 * manifest file. Named for the same reason as [manifestSegments], and deliberately
 * kept separate from it: one adds a segment and one removes one, and folding them
 * into a single `withManifest(present: Boolean)` would make both call sites read the
 * caller's flag rather than their own intent.
 */
internal fun baseSegments(pathSegments: List<String>): List<String> =
    if (pathSegments.lastOrNull().equals(MANIFEST_SEGMENT, ignoreCase = true)) {
        pathSegments.dropLast(1)
    } else {
        pathSegments
    }

internal fun normalizeManifestUrl(raw: String): String? {
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

    // All three branches above emit `://`, and the first one has already rewritten any
    // `stremio://`, so the scheme can no longer be null, blank or `stremio`. The old
    // code still rebuilt the uri through a `when (scheme) { null, "", "stremio" ->
    // buildUpon().scheme("https") }` arm, and that arm was dead before this port began:
    // UriBehaviourHostTest found the same dead branch in :addons' copy of these rules.
    // It is deliberately NOT carried across. Reproducing it would be porting dead code,
    // and it is the kind of arm that reads as a live guard to whoever edits this next.
    //
    // The `uri.encodedAuthority ?: host` fallback is dead for the same reason:
    // `ManifestUri.parse` already rejects a blank host, so the fallback cannot fire.
    val uri = ManifestUri.parse(normalizedInput) ?: return null
    val url = uri.baseUrlFor(manifestSegments(uri.pathSegments))
    val query = uri.encodedQuery?.takeIf { encodedQuery -> encodedQuery.isNotBlank() }
    return if (query == null) url else "$url?$query"
}

internal fun addonBaseUrl(manifestUrl: String): String {
    // A url `ManifestUri` declines to parse used to reach `Uri.Builder` with a null
    // scheme and a null authority, which emitted `scheme:/...`-shaped nonsense instead of
    // the input. It now comes back as the input with its trailing slash trimmed -- which
    // is all the caller does with it anyway, since the only uses are joining and
    // trimming. Returning the input beats returning a mangled version of it.
    val uri = ManifestUri.parse(manifestUrl) ?: return manifestUrl.trimEnd('/')
    return uri.baseUrlFor(baseSegments(uri.pathSegments))
}

internal fun resolveAddonAssetUrl(baseUrl: String, rawAssetUrl: String?): String? {
    val assetUrl = rawAssetUrl?.trim().orEmpty()
    if (assetUrl.isEmpty()) {
        return null
    }

    // The old first arm was `Uri.parse(assetUrl).scheme != null`, and it is NOT
    // reproducible with `ManifestUri.parse`: that requires `://` and a non-blank host, so
    // a `data:` or `mailto:` asset would fall through and get joined onto the base url,
    // turning an absolute asset into a path under the add-on's manifest. The question
    // being asked is only "does this string name its own scheme", so it is asked with a
    // scheme pattern rather than with a parser -- and the pattern is what keeps the
    // schemeless forms (`//host/x`, `/x`, `x`) falling through to the three relative
    // arms, which is the behaviour being preserved.
    if (ABSOLUTE_URL_SCHEME_REGEX.containsMatchIn(assetUrl)) {
        return assetUrl
    }

    val baseUri = ManifestUri.parse(baseUrl)
    val baseScheme = baseUri?.scheme ?: "https"
    val authority = baseUri?.encodedAuthority.orEmpty()
    return when {
        // The old middle arm used `Uri.Builder().encodedPath(assetUrl)`, which takes the
        // path already encoded and does not encode it -- so composing the string is the
        // same work, not a shortcut past it.
        assetUrl.startsWith("//") -> "$baseScheme:$assetUrl"
        assetUrl.startsWith("/") -> "$baseScheme://$authority$assetUrl"
        else -> "${baseUrl.trimEnd('/')}/$assetUrl"
    }
}

internal fun manifestUrlsMatch(left: String, right: String): Boolean {
    return left.trim().equals(right.trim(), ignoreCase = true)
}

private suspend fun httpGetJson(httpClient: CrispyHttpClient, url: String): JsonObject? {
    val response =
        runCatching {
            httpClient.get(
                url = url,
                headers = mapOf("Accept" to "application/json"),
                callTimeoutMs = 12_000L,
            )
        }.getOrNull() ?: return null

    if (response.code !in 200..299) {
        return null
    }
    val body = response.body
    if (body.isBlank()) {
        return null
    }
    return runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
}

private fun nonBlank(value: String?): String? {
    val trimmed = value?.trim()
    return if (trimmed.isNullOrEmpty()) null else trimmed
}

private val URI_SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

/**
 * A scheme with no `://`, which [URI_SCHEME_REGEX] deliberately does not match.
 *
 * `resolveAddonAssetUrl` has to recognise an asset that names its own scheme without
 * requiring `://`, because `data:` and `mailto:` do not carry one and must still be
 * treated as absolute. That is the one question a parser answers worse than a pattern
 * does, which is why this is a second pattern rather than a reach for `ManifestUri`.
 */
private val ABSOLUTE_URL_SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

private const val MANIFEST_SEGMENT = "manifest.json"

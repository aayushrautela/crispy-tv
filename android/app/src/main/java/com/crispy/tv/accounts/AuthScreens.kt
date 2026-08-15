@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.crispy.tv.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crispy.tv.ui.components.StandardTopAppBar
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import com.crispy.tv.ui.utils.appBarScrollBehavior

private val NetflixProfileBackground = Color(0xFF141414)
private val NetflixTileFallback = Color(0xFF333333)
private val NetflixTileShape = RoundedCornerShape(6.dp)
private val NetflixTileSize = 120.dp

@Composable
fun AuthRoute(
    onSignedIn: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val viewModel: AuthViewModel = viewModel(factory = remember(appContext) { AuthViewModel.factory(appContext) })
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.signedIn) {
        if (uiState.signedIn) onSignedIn()
    }

    AuthScreen(
        uiState = uiState,
        onEmailChange = viewModel::onEmailChange,
        onPasswordChange = viewModel::onPasswordChange,
        onNameChange = viewModel::onNameChange,
        onLanguageChange = viewModel::onLanguageChange,
        onAvatarIdChange = viewModel::onAvatarIdChange,
        onSignIn = viewModel::signIn,
        onSignUp = viewModel::signUp,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthScreen(
    uiState: AuthUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onLanguageChange: (String) -> Unit,
    onAvatarIdChange: (String) -> Unit,
    onSignIn: () -> Unit,
    onSignUp: () -> Unit,
) {
    var mode by remember { mutableStateOf(1) }
    val scrollBehavior = appBarScrollBehavior()
    val pageHorizontalPadding = responsivePageHorizontalPadding()
    val languages = remember { com.crispy.tv.domain.account.SUPPORTED_LANGUAGES }
    var languageMenuOpen by remember { mutableStateOf(false) }
    var avatarDialogOpen by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            StandardTopAppBar(
                title = if (mode == 0) "Sign in" else "Create account",
                scrollBehavior = scrollBehavior,
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(
                start = pageHorizontalPadding,
                end = pageHorizontalPadding,
                top = Dimensions.SectionSpacing,
                bottom = Dimensions.PageBottomPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (uiState.error != null) {
                item {
                    Text(
                        text = uiState.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (uiState.info != null) {
                item {
                    Text(
                        text = uiState.info,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            item {
                OutlinedTextField(
                    value = uiState.email,
                    onValueChange = onEmailChange,
                    label = { Text("Email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = uiState.password,
                    onValueChange = onPasswordChange,
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (mode == 1) {
                item {
                    OutlinedTextField(
                        value = uiState.displayName,
                        onValueChange = onNameChange,
                        label = { Text("Display name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = languages.firstOrNull { it.code == uiState.interfaceLanguage }?.name ?: uiState.interfaceLanguage,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Language") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(onClick = { languageMenuOpen = true }) {
                                    Icon(Icons.Outlined.ArrowDropDown, contentDescription = "Select language")
                                }
                            },
                        )
                        androidx.compose.material3.DropdownMenu(
                            expanded = languageMenuOpen,
                            onDismissRequest = { languageMenuOpen = false },
                        ) {
                            languages.forEach { lang ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(lang.name) },
                                    onClick = {
                                        onLanguageChange(lang.code)
                                        languageMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                }
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        coil3.compose.AsyncImage(
                            model = uiState.avatarUrl,
                            contentDescription = "Avatar preview",
                            modifier = Modifier.size(48.dp),
                        )
                        OutlinedButton(onClick = { avatarDialogOpen = true }) {
                            Text("Pick avatar")
                        }
                    }
                }
            }

            if (mode == 0) {
                item {
                    Button(
                        onClick = onSignIn,
                        enabled = !uiState.isBusy && uiState.email.isNotBlank() && uiState.password.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (uiState.isBusy) LoadingIndicator(modifier = Modifier.padding(end = 8.dp)) else Text("Sign in")
                    }
                }
                item {
                    TextButton(
                        onClick = { mode = 1 },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Create account")
                    }
                }
            } else {
                item {
                    Button(
                        onClick = onSignUp,
                        enabled = !uiState.isBusy && uiState.email.isNotBlank() && uiState.password.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (uiState.isBusy) LoadingIndicator(modifier = Modifier.padding(end = 8.dp)) else Text("Create account")
                    }
                }
                item {
                    TextButton(
                        onClick = { mode = 0 },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Already have an account? Sign in")
                    }
                }
            }
        }
    }

    if (avatarDialogOpen) {
        AlertDialog(
            onDismissRequest = { avatarDialogOpen = false },
            confirmButton = {
                TextButton(onClick = { avatarDialogOpen = false }) { Text("Done") }
            },
            title = { Text("Pick avatar") },
            text = { AvatarPickerGrid(selectedId = uiState.avatarId, onAvatarIdChange = onAvatarIdChange) },
        )
    }
}

@Composable
fun ProfileSelectorRoute(
    onComplete: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val viewModel: ProfileListViewModel = viewModel(factory = remember(appContext) { ProfileListViewModel.factory(appContext) })
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }
    // Selecting a profile (tapping it) is what enters the app; adding profiles keeps
    // you on this hub. The Trakt/Simkl sync provider is configured later in Account Settings.
    LaunchedEffect(uiState.justSelected) {
        if (uiState.justSelected) onComplete()
    }

    ProfileSelectorScreen(
        uiState = uiState,
        onBack = onBack,
        onSelectProfile = viewModel::selectProfile,
        onFinishSetup = viewModel::finishSetup,
        onCreateProfile = viewModel::createProfile,
        onDialogNameChange = viewModel::onNameChange,
        onDialogKidsToggle = viewModel::onKidsToggle,
        onDialogAvatarIdChange = viewModel::onAvatarIdChange,
        onOpenCreateDialog = viewModel::openCreateDialog,
        onDismissDialog = viewModel::dismissDialog,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSelectorScreen(
    uiState: ProfileListUiState,
    onBack: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onFinishSetup: (name: String, language: String, avatarId: String) -> Unit,
    onCreateProfile: (String, Boolean, String) -> Unit,
    onDialogNameChange: (String) -> Unit,
    onDialogKidsToggle: (Boolean) -> Unit,
    onDialogAvatarIdChange: (String) -> Unit,
    onOpenCreateDialog: () -> Unit,
    onDismissDialog: () -> Unit,
) {
    val pageHorizontalPadding = responsivePageHorizontalPadding()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NetflixProfileBackground),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = pageHorizontalPadding)
                .padding(top = 64.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val isSetup = uiState.profiles.isEmpty()
            Text(
                text = if (isSetup) "Finish setting up" else "Who's watching?",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )

            uiState.error?.let { error ->
                Text(text = error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            if (isSetup) {
                if (uiState.isBusy) {
                    LoadingIndicator(color = Color.White)
                } else {
                    ProfileSetupScreen(onFinishSetup = onFinishSetup)
                }
            } else {
                if (uiState.isBusy && uiState.profiles.isEmpty()) {
                    LoadingIndicator(color = Color.White)
                } else {
                    ProfileGrid(
                        profiles = uiState.profiles,
                        onSelectProfile = onSelectProfile,
                        onAddProfile = onOpenCreateDialog,
                    )
                }

                Spacer(modifier = Modifier.size(24.dp))

                TextButton(onClick = onBack) {
                    Text(
                        text = "Sign out",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }
    }

    if (uiState.dialogOpen) {
        AlertDialog(
            onDismissRequest = onDismissDialog,
            confirmButton = {
                TextButton(
                    onClick = { onCreateProfile(uiState.dialogName, uiState.dialogIsKids, uiState.dialogAvatarId) },
                    enabled = uiState.dialogName.isNotBlank(),
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDialog) { Text("Cancel") }
            },
            title = { Text("New profile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = uiState.dialogName,
                        onValueChange = onDialogNameChange,
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(text = "Kids profile")
                        Switch(checked = uiState.dialogIsKids, onCheckedChange = onDialogKidsToggle)
                    }
                    AvatarPickerGrid(selectedId = uiState.dialogAvatarId, onAvatarIdChange = onDialogAvatarIdChange)
                    uiState.dialogError?.let { error ->
                        Text(text = error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
        )
    }
}

@Composable
private fun ProfileSetupScreen(onFinishSetup: (name: String, language: String, avatarId: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("en") }
    var avatarId by remember { mutableStateOf("avatar_01") }
    val languages = remember { com.crispy.tv.domain.account.SUPPORTED_LANGUAGES }
    var languageMenuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Tell us a bit about your profile.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Display name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = languages.firstOrNull { it.code == language }?.name ?: language,
                onValueChange = {},
                readOnly = true,
                label = { Text("Language") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = { languageMenuOpen = true }) {
                        Icon(Icons.Outlined.ArrowDropDown, contentDescription = "Select language")
                    }
                },
            )
            androidx.compose.material3.DropdownMenu(
                expanded = languageMenuOpen,
                onDismissRequest = { languageMenuOpen = false },
            ) {
                languages.forEach { lang ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(lang.name) },
                        onClick = {
                            language = lang.code
                            languageMenuOpen = false
                        },
                    )
                }
            }
        }

        AvatarPickerGrid(selectedId = avatarId, onAvatarIdChange = { avatarId = it })

        Button(
            onClick = { onFinishSetup(name.trim(), language, avatarId) },
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Finish")
        }
    }
}

@Composable
private fun ProfileGrid(
    profiles: List<ProfileListItem>,
    onSelectProfile: (String) -> Unit,
    onAddProfile: () -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        profiles.forEach { profile ->
            ProfileCard(
                name = profile.name,
                avatarUrl = profile.avatarUrl,
                onClick = { onSelectProfile(profile.id) },
            )
        }
        ProfileAddCard(onClick = onAddProfile)
    }
}

@Composable
private fun ProfileCard(name: String, avatarUrl: String?, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .width(NetflixTileSize + 8.dp)
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(NetflixTileSize)
                .clip(NetflixTileShape)
                .background(NetflixTileFallback),
            contentAlignment = Alignment.Center,
        ) {
            if (avatarUrl != null) {
                coil3.compose.AsyncImage(
                    model = avatarUrl,
                    contentDescription = name,
                    modifier = Modifier.fillMaxSize().clip(NetflixTileShape),
                )
            } else {
                Icon(
                    Icons.Outlined.AccountCircle,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(72.dp),
                )
            }
        }
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun ProfileAddCard(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .width(NetflixTileSize + 8.dp)
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(NetflixTileSize)
                .clip(NetflixTileShape)
                .border(2.dp, Color.White.copy(alpha = 0.5f), NetflixTileShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Add,
                contentDescription = "Add profile",
                tint = Color.White,
                modifier = Modifier.size(56.dp),
            )
        }
        Text(
            text = "Add profile",
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun ProfileManagementRoute(
    onBack: () -> Unit,
    onOpenAccountSettings: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val viewModel: ProfileListViewModel = viewModel(factory = remember(appContext) { ProfileListViewModel.factory(appContext) })
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }

    ProfileManagementScreen(
        uiState = uiState,
        onBack = onBack,
        onOpenAccountSettings = onOpenAccountSettings,
        onCreateProfile = viewModel::createProfile,
        onDialogNameChange = viewModel::onNameChange,
        onDialogKidsToggle = viewModel::onKidsToggle,
        onDialogAvatarIdChange = viewModel::onAvatarIdChange,
        onOpenCreateDialog = viewModel::openCreateDialog,
        onDismissDialog = viewModel::dismissDialog,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileManagementScreen(
    uiState: ProfileListUiState,
    onBack: () -> Unit,
    onOpenAccountSettings: () -> Unit,
    onCreateProfile: (String, Boolean, String) -> Unit,
    onDialogNameChange: (String) -> Unit,
    onDialogKidsToggle: (Boolean) -> Unit,
    onDialogAvatarIdChange: (String) -> Unit,
    onOpenCreateDialog: () -> Unit,
    onDismissDialog: () -> Unit,
) {
    val scrollBehavior = appBarScrollBehavior()
    val pageHorizontalPadding = responsivePageHorizontalPadding()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            StandardTopAppBar(
                title = "Profiles",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenCreateDialog) {
                        Icon(Icons.Outlined.Add, contentDescription = "Add profile")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(
                start = pageHorizontalPadding,
                end = pageHorizontalPadding,
                top = Dimensions.SectionSpacing,
                bottom = Dimensions.PageBottomPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            uiState.error?.let { error ->
                item {
                    Text(text = error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            if (uiState.isBusy && uiState.profiles.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        LoadingIndicator()
                    }
                }
            }
            items(uiState.profiles.size) { index ->
                val profile = uiState.profiles[index]
                ProfileRow(name = profile.name, isKids = profile.isKids, avatarUrl = profile.avatarUrl)
            }
            item {
                OutlinedButton(onClick = onOpenAccountSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Account settings")
                }
            }
        }
    }

    if (uiState.dialogOpen) {
        AlertDialog(
            onDismissRequest = onDismissDialog,
            confirmButton = {
                TextButton(
                    onClick = { onCreateProfile(uiState.dialogName, uiState.dialogIsKids, uiState.dialogAvatarId) },
                    enabled = uiState.dialogName.isNotBlank(),
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDialog) { Text("Cancel") }
            },
            title = { Text("New profile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = uiState.dialogName,
                        onValueChange = onDialogNameChange,
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(text = "Kids profile")
                        Switch(checked = uiState.dialogIsKids, onCheckedChange = onDialogKidsToggle)
                    }
                    AvatarPickerGrid(selectedId = uiState.dialogAvatarId, onAvatarIdChange = onDialogAvatarIdChange)
                    uiState.dialogError?.let { error ->
                        Text(text = error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
        )
    }
}

@Composable
private fun ProfileRow(name: String, isKids: Boolean, avatarUrl: String?) {
    ListItem(
        headlineContent = { Text(text = name) },
        supportingContent = if (isKids) ({ Text(text = "Kids") }) else null,
        leadingContent = {
            if (avatarUrl != null) {
                AvatarImage(url = avatarUrl, contentDescription = name)
            } else {
                Icon(Icons.Outlined.AccountCircle, contentDescription = null)
            }
        },
    )
}

@Composable
private fun AvatarImage(url: String, contentDescription: String) {
    coil3.compose.AsyncImage(
        model = url,
        contentDescription = contentDescription,
        modifier = Modifier
            .fillMaxWidth()
            .padding(Dimensions.ListItemPadding),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AvatarPickerGrid(selectedId: String, onAvatarIdChange: (String) -> Unit) {
    val avatarIds = remember { com.crispy.tv.domain.account.SUPPORTED_AVATAR_IDS }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        avatarIds.forEach { id ->
            val url = com.crispy.tv.avatar.AvatarUrlResolver.builtInAvatarUrl(id)
            val selected = id == selectedId
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
                    .clickable { onAvatarIdChange(id) },
                contentAlignment = Alignment.Center,
            ) {
                coil3.compose.AsyncImage(
                    model = url,
                    contentDescription = id,
                    modifier = Modifier.size(56.dp).clip(CircleShape),
                )
            }
        }
    }
}

@Composable
fun AccountSettingsRoute(
    onBack: () -> Unit,
    onSignedOut: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val viewModel: AccountSettingsViewModel = viewModel(factory = remember(appContext) { AccountSettingsViewModel.factory(appContext) })
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(uiState.deleted) {
        if (uiState.deleted) onSignedOut()
    }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        viewModel.consumePendingProviderAuth()
        onPauseOrDispose { }
    }

    AccountSettingsScreen(
        uiState = uiState,
        onBack = onBack,
        onSelectTrakt = { viewModel.startImport("trakt") },
        onSelectSimkl = { viewModel.startImport("simkl") },
        onClearSync = viewModel::disconnectSyncProvider,
        onDeleteAccount = viewModel::deleteAccount,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountSettingsScreen(
    uiState: AccountSettingsUiState,
    onBack: () -> Unit,
    onSelectTrakt: () -> Unit,
    onSelectSimkl: () -> Unit,
    onClearSync: () -> Unit,
    onDeleteAccount: () -> Unit,
) {
    val scrollBehavior = appBarScrollBehavior()
    val pageHorizontalPadding = responsivePageHorizontalPadding()
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            StandardTopAppBar(
                title = "Account settings",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(
                start = pageHorizontalPadding,
                end = pageHorizontalPadding,
                top = Dimensions.SectionSpacing,
                bottom = Dimensions.PageBottomPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            uiState.error?.let { error ->
                item { Text(text = error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            }
            uiState.statusMessage?.let { message ->
                item { Text(text = message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(Dimensions.CardInternalPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = "Plan", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = uiState.pricingTier?.replaceFirstChar { it.uppercase() } ?: "—",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = if (uiState.hasMdbListAccess) "MDBList access enabled" else "MDBList access not enabled",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth().clickable(enabled = !uiState.isBusy, onClick = onSelectTrakt)) {
                    Column(modifier = Modifier.padding(Dimensions.CardInternalPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = "Sync service", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = uiState.syncProvider?.takeIf { it.isNotBlank() }
                                ?.replaceFirstChar { it.uppercase() }
                                ?: "Not connected",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = onSelectTrakt,
                                enabled = !uiState.isBusy,
                            ) { Text("Trakt") }
                            OutlinedButton(
                                onClick = onSelectSimkl,
                                enabled = !uiState.isBusy,
                            ) { Text("SIMKL") }
                            if (uiState.syncProvider?.isNotBlank() == true) {
                                OutlinedButton(
                                    onClick = onClearSync,
                                    enabled = !uiState.isBusy,
                                ) { Text("Disconnect") }
                            }
                        }
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    enabled = !uiState.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Delete account")
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDeleteAccount() }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
            title = { Text("Delete account?") },
            text = { Text("This permanently deletes your account and cannot be undone.") },
        )
    }
}

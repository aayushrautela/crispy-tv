package com.crispy.rewrite.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.VideoSettings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

data class SettingsItem(
    val label: String,
    val description: String? = null,
    val icon: ImageVector,
    val iconTint: androidx.compose.ui.graphics.Color,
    val onClick: () -> Unit = {}
)

data class SettingsGroup(
    val title: String,
    val items: List<SettingsItem>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToHomeScreenSettings: () -> Unit = {},
    onNavigateToAddonsSettings: () -> Unit = {},
    onNavigateToPlaybackSettings: () -> Unit = {},
    onNavigateToLabs: () -> Unit = {},
    onNavigateToProviderPortal: () -> Unit = {}
) {
    val scrollState = rememberScrollState()

    val settingsGroups = listOf(
        SettingsGroup(
            title = "ACCOUNT",
            items = listOf(
                SettingsItem(
                    label = "Profiles",
                    description = "Manage user profiles and preferences",
                    icon = Icons.Outlined.Person,
                    iconTint = MaterialTheme.colorScheme.primary
                ),
                SettingsItem(
                    label = "Trakt + Simkl",
                    description = "Provider login portal",
                    icon = Icons.Outlined.Cloud,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    onClick = onNavigateToProviderPortal
                )
            )
        ),
        SettingsGroup(
            title = "GENERAL",
            items = listOf(
                SettingsItem(
                    label = "Appearance",
                    description = "Theme, colors, and display options",
                    icon = Icons.Outlined.Brush,
                    iconTint = MaterialTheme.colorScheme.secondary
                ),
                SettingsItem(
                    label = "Language",
                    description = "App language and region",
                    icon = Icons.Outlined.Language,
                    iconTint = MaterialTheme.colorScheme.primary
                )
            )
        ),
        SettingsGroup(
            title = "PLAYER",
            items = listOf(
                SettingsItem(
                    label = "Playback",
                    description = "Quality, buffering, and intro skip settings",
                    icon = Icons.Outlined.VideoSettings,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    onClick = onNavigateToPlaybackSettings
                ),
                SettingsItem(
                    label = "Subtitles",
                    description = "Subtitle appearance and language",
                    icon = Icons.Outlined.ClosedCaption,
                    iconTint = MaterialTheme.colorScheme.secondary
                )
            )
        ),
        SettingsGroup(
            title = "CONTENT",
            items = listOf(
                SettingsItem(
                    label = "Home Screen",
                    description = "Customize your home feed",
                    icon = Icons.Outlined.Home,
                    iconTint = MaterialTheme.colorScheme.primary,
                    onClick = onNavigateToHomeScreenSettings
                ),
                SettingsItem(
                    label = "Addons",
                    description = "Manage streaming addons",
                    icon = Icons.Outlined.Extension,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    onClick = onNavigateToAddonsSettings
                ),
                SettingsItem(
                    label = "Search",
                    description = "Search providers and filters",
                    icon = Icons.Outlined.Search,
                    iconTint = MaterialTheme.colorScheme.secondary
                )
            )
        ),
        SettingsGroup(
            title = "SERVICES",
            items = listOf(
                SettingsItem(
                    label = "Metadata Providers",
                    description = "TMDB, Cinemeta, and more",
                    icon = Icons.Outlined.Storage,
                    iconTint = MaterialTheme.colorScheme.primary
                ),
                SettingsItem(
                    label = "AI Insights",
                    description = "Smart recommendations",
                    icon = Icons.Outlined.AutoAwesome,
                    iconTint = MaterialTheme.colorScheme.tertiary
                )
            )
        ),
        SettingsGroup(
            title = "LABS",
            items = listOf(
                SettingsItem(
                    label = "Experimental Features",
                    description = "Playback lab, metadata tools, and sync",
                    icon = Icons.Outlined.Science,
                    iconTint = MaterialTheme.colorScheme.error,
                    onClick = onNavigateToLabs
                )
            )
        ),
        SettingsGroup(
            title = "ABOUT",
            items = listOf(
                SettingsItem(
                    label = "Version",
                    description = "Crispy v0.1.0",
                    icon = Icons.Outlined.Info,
                    iconTint = MaterialTheme.colorScheme.secondary
                ),
                SettingsItem(
                    label = "Open Source",
                    description = "Licenses and acknowledgments",
                    icon = Icons.Outlined.Code,
                    iconTint = MaterialTheme.colorScheme.primary
                )
            )
        )
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Settings",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            text = "Customize your experience",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            
            settingsGroups.forEach { group ->
                SettingsGroupCard(group = group)
            }
            
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsGroupCard(
    group: SettingsGroup
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = group.title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
        )
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                group.items.forEachIndexed { index, item ->
                    SettingsItemRow(item = item)
                    
                    if (index < group.items.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 68.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsItemRow(
    item: SettingsItem
) {
    ListItem(
        headlineContent = {
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyLarge
            )
        },
        supportingContent = item.description?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(item.iconTint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    tint = item.iconTint,
                    modifier = Modifier.size(22.dp)
                )
            }
        },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = item.onClick)
    )
}

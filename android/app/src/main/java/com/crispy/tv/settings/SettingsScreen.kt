package com.crispy.tv.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.VideoSettings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import com.crispy.tv.ui.components.StandardTopAppBar

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
    onNavigateToAddonsSettings: () -> Unit = {},
    onNavigateToPlaybackSettings: () -> Unit = {},
    onNavigateToAiInsightsSettings: () -> Unit = {},
    onNavigateToProviderPortal: () -> Unit = {}
) {
    val scrollState = rememberScrollState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    val settingsGroups =
        listOf(
            SettingsGroup(
                title = "PERSONALIZATION",
                items =
                    listOf(
                        SettingsItem(
                            label = "Playback",
                            description = "Player defaults and intro controls",
                            icon = Icons.Outlined.VideoSettings,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            onClick = onNavigateToPlaybackSettings
                        ),
                        SettingsItem(
                            label = "Subtitles",
                            description = "Caption styling and defaults",
                            icon = Icons.Outlined.ClosedCaption,
                            iconTint = MaterialTheme.colorScheme.secondary
                        )
                    )
            ),
            SettingsGroup(
                title = "INTEGRATIONS",
                items =
                    listOf(
                        SettingsItem(
                            label = "Addons",
                            description = "Install and remove addon manifests",
                            icon = Icons.Outlined.Extension,
                            iconTint = MaterialTheme.colorScheme.primary,
                            onClick = onNavigateToAddonsSettings
                        ),
                        SettingsItem(
                            label = "Provider Login Portal",
                            description = "Connect Trakt and Simkl accounts",
                            icon = Icons.Outlined.Cloud,
                            iconTint = MaterialTheme.colorScheme.tertiary,
                            onClick = onNavigateToProviderPortal
                        ),
                        SettingsItem(
                            label = "Language & Region",
                            description = "Preferred content language",
                            icon = Icons.Outlined.Language,
                            iconTint = MaterialTheme.colorScheme.primary
                        )
                    )
            ),
            SettingsGroup(
                title = "SYSTEM",
                items =
                    listOf(
                        SettingsItem(
                            label = "AI Insights",
                            description = "Model and API key",
                            icon = Icons.Outlined.AutoAwesome,
                            iconTint = MaterialTheme.colorScheme.primary,
                            onClick = onNavigateToAiInsightsSettings
                        ),
                        SettingsItem(
                            label = "About",
                            description = "Version, licenses, and credits",
                            icon = Icons.Outlined.Info,
                            iconTint = MaterialTheme.colorScheme.secondary
                        )
                    )
            )
        )

    val pageHorizontalPadding = responsivePageHorizontalPadding()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            StandardTopAppBar(
                title = "Settings",
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SectionSpacing)
        ) {
            Text(
                text = "Customize your experience",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = pageHorizontalPadding)
            )

            settingsGroups.forEach { group ->
                SettingsGroupCard(group = group)
            }

            Spacer(modifier = Modifier.height(Dimensions.ListItemPadding))
        }
    }
}

@Composable
private fun SettingsGroupCard(
    group: SettingsGroup
) {
    val pageHorizontalPadding = responsivePageHorizontalPadding()
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = pageHorizontalPadding)
    ) {
        Text(
            text = group.title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = Dimensions.ListItemPadding, bottom = 8.dp)
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

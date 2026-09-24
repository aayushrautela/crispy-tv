package com.crispy.tv.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.annotation.DrawableRes
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crispy.tv.ui.assets.R
import com.crispy.tv.ui.components.CrispyIcon
import com.crispy.tv.ui.components.StandardTopAppBar
import com.crispy.tv.ui.components.topLevelAppBarColors
import com.crispy.tv.ui.edge_to_edge.safeBottomPadding
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import com.crispy.tv.ui.utils.appBarScrollBehavior

data class SettingsItem(
    val label: String,
    val description: String? = null,
    @DrawableRes val icon: Int,
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
    onNavigateToPluginsSettings: () -> Unit = {},
    onNavigateToPlaybackSettings: () -> Unit = {},
    onNavigateToImageSettings: () -> Unit = {},
    onNavigateToAccountsProfiles: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    val scrollState = rememberScrollState()
    val scrollBehavior = appBarScrollBehavior()

    val settingsGroups =
        listOf(
            SettingsGroup(
                title = "ACCOUNT",
                items =
                    listOf(
                        SettingsItem(
                            label = "Account and subscription",
                            description = "Manage your account, subscription, profiles, and billing",
                            icon = R.drawable.ic_person,
                            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = onNavigateToAccountsProfiles
                        )
                    )
            ),
            SettingsGroup(
                title = "PERSONALIZATION",
                items =
                    listOf(
                        SettingsItem(
                            label = "Playback",
                            description = "Player defaults and intro controls",
                            icon = R.drawable.ic_video_settings,
                            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = onNavigateToPlaybackSettings
                        ),
                        SettingsItem(
                            label = "Image Quality",
                            description = "Choose artwork detail and cache size",
                            icon = R.drawable.ic_image,
                            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = onNavigateToImageSettings
                        ),
                        SettingsItem(
                            label = "Subtitles",
                            description = "Caption styling and defaults",
                            icon = R.drawable.ic_closed_caption,
                            iconTint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
            ),
            SettingsGroup(
                title = "INTEGRATIONS",
                items =
                    buildList {
                        if (PluginsUiSupported) {
                            add(
                                SettingsItem(
                                    label = "Plugins",
                                    description = "JavaScript plugin repositories",
                                    icon = R.drawable.ic_build,
                                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    onClick = onNavigateToPluginsSettings,
                                ),
                            )
                        }
                        add(
                            SettingsItem(
                                label = "Addons",
                                description = "Install and remove addon manifests",
                                icon = R.drawable.ic_extension,
                                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                                onClick = onNavigateToAddonsSettings,
                            ),
                        )
                        add(
                            SettingsItem(
                                label = "Language & Region",
                                description = "Preferred content language",
                                icon = R.drawable.ic_language,
                                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    },
            ),
            SettingsGroup(
                title = "SYSTEM",
                items =
                    listOf(
                        SettingsItem(
                            label = "About",
                            description = "Version, licenses, and credits",
                            icon = R.drawable.ic_info,
                            iconTint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
            )
        )
    
    val pageHorizontalPadding = responsivePageHorizontalPadding()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            StandardTopAppBar(
                title = "Settings",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        CrispyIcon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "Back",
                            autoMirror = true,
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = topLevelAppBarColors(),
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
                .verticalScroll(scrollState)
                .padding(bottom = safeBottomPadding(Dimensions.ListItemPadding)),
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                CrispyIcon(
                    painter = painterResource(item.icon),
                    contentDescription = null,
                    tint = item.iconTint,
                    modifier = Modifier.size(22.dp)
                )
            }
        },
        trailingContent = {
            CrispyIcon(
                painter = painterResource(R.drawable.ic_keyboard_arrow_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                autoMirror = true,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = item.onClick)
    ) {
        Text(
            text = item.label,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

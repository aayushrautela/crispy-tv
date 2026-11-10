package com.crispy.tv.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.crispy.tv.ui.components.CrispyIcon
import com.crispy.tv.ui.components.StandardTopAppBar
import com.crispy.tv.ui.edge_to_edge.safeBottomPadding
import com.crispy.tv.ui.resources.Res
import com.crispy.tv.ui.resources.ic_arrow_back
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.utils.appBarScrollBehavior
import org.jetbrains.compose.resources.painterResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackSettingsScreen(
    settings: PlaybackSettings,
    onTrailerAutoplayChanged: (Boolean) -> Unit,
    onSkipIntroChanged: (Boolean) -> Unit,
    onAutoSelectStreamChanged: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val scrollBehavior = appBarScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            StandardTopAppBar(
                title = "Playback",
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
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(Dimensions.ListItemPadding)
                .padding(bottom = safeBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    supportingContent = {
                        Text("Automatically start the details-page trailer once the hero settles.")
                    },
                    trailingContent = {
                        Switch(
                            checked = settings.trailerAutoplayEnabled,
                            onCheckedChange = onTrailerAutoplayChanged,
                            colors = androidx.compose.material3.SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                                checkedThumbColor = MaterialTheme.colorScheme.onSurface,
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                ) {
                    Text("Trailer Autoplay")
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    supportingContent = {
                        Text("Show skip controls for intro, recap, and ending segments.")
                    },
                    trailingContent = {
                        Switch(
                            checked = settings.skipIntroEnabled,
                            onCheckedChange = onSkipIntroChanged,
                            colors = androidx.compose.material3.SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                                checkedThumbColor = MaterialTheme.colorScheme.onSurface,
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                ) {
                    Text("Skip Intro")
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    supportingContent = {
                        Text("When a source is picked from details, start playback automatically instead of opening the stream sheet.")
                    },
                    trailingContent = {
                        Switch(
                            checked = settings.autoSelectStream,
                            onCheckedChange = onAutoSelectStreamChanged,
                            colors = androidx.compose.material3.SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                                checkedThumbColor = MaterialTheme.colorScheme.onSurface,
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                ) {
                    Text("Auto-select Stream")
                }
            }

            Text(
                text = "Segment data is fetched from IntroDB first with AniSkip fallback.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

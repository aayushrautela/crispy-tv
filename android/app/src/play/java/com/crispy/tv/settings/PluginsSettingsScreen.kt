package com.crispy.tv.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.crispy.tv.ui.components.StandardTopAppBar
import com.crispy.tv.ui.utils.appBarScrollBehavior

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsSettingsRoute(onBack: () -> Unit) {
    Scaffold(
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
                scrollBehavior = appBarScrollBehavior(),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Plugins are only available in the open-source (FOSS) build.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

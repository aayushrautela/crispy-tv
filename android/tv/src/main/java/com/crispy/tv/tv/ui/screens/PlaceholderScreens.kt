package com.crispy.tv.tv.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@Composable
fun SearchScreen(modifier: Modifier = Modifier) {
    Placeholder("Search — Phase 5", modifier)
}

@Composable
fun LibraryScreen(modifier: Modifier = Modifier) {
    Placeholder("Library — Phase 5", modifier)
}

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    Placeholder("Settings — Phase 5", modifier)
}

@Composable
private fun Placeholder(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(48.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

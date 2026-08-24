package com.crispy.tv.tv.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.crispy.tv.tv.ui.components.RailSection

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroll),
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        RailSection(
            title = "Continue Watching",
            items = sampleRail("cw"),
            onItemClick = {},
        )
        RailSection(
            title = "Up Next",
            items = sampleRail("upnext"),
            onItemClick = {},
        )
        RailSection(
            title = "Popular Movies",
            items = sampleRail("popular"),
            onItemClick = {},
        )
        Spacer(modifier = Modifier.height(32.dp))
    }
}

package com.crispy.tv.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow

@Composable
fun standardTopAppBarColors(): TopAppBarColors {
    return TopAppBarDefaults.topAppBarColors(
        containerColor = Color.Transparent,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StandardTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    colors: TopAppBarColors? = null,
    windowInsets: WindowInsets = WindowInsets(0, 0, 0, 0)
) {
    val resolvedColors = colors ?: standardTopAppBarColors()
    TopAppBar(
        title = title,
        modifier = modifier.statusBarsPadding(),
        navigationIcon = navigationIcon,
        actions = actions,
        scrollBehavior = scrollBehavior,
        colors = resolvedColors,
        windowInsets = windowInsets
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StandardTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    colors: TopAppBarColors? = null,
    windowInsets: WindowInsets = WindowInsets(0, 0, 0, 0)
) {
    StandardTopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        modifier = modifier,
        navigationIcon = navigationIcon,
        actions = actions,
        scrollBehavior = scrollBehavior,
        colors = colors,
        windowInsets = windowInsets
    )
}

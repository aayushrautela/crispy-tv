package com.crispy.tv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.crispy.tv.ui.brand.CrispyWordmark
import com.crispy.tv.ui.components.ProfileIconButton
import com.crispy.tv.ui.components.StandardTopAppBar
import com.crispy.tv.ui.navigation.AppNavHost
import com.crispy.tv.ui.navigation.AppRoutes
import com.crispy.tv.ui.navigation.TopLevelDestination
import com.crispy.tv.ui.utils.appBarScrollBehavior
import com.crispy.tv.ui.utils.resetHeightOffset
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val currentDestination = TopLevelDestination.entries.firstOrNull { it.route == currentRoute }
    val bottomBarDestinations = remember { TopLevelDestination.entries.filter { it.showInBottomBar } }
    val showBottomBar = currentDestination?.showInBottomBar == true
    val showTopBar = currentDestination?.showTopBar == true
    val appChromeInsets = rememberAppChromeInsets(showTopBar = showTopBar, showBottomBar = showBottomBar)
    val topAppBarScrollBehavior = appBarScrollBehavior(canScroll = { showTopBar })
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(currentRoute) {
        topAppBarScrollBehavior.state.resetHeightOffset()
    }

    CompositionLocalProvider(LocalAppChromeInsets provides appChromeInsets) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            modifier =
                Modifier
                    .fillMaxSize()
                    .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
            topBar = {
                AnimatedVisibility(
                    visible = showTopBar,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    when (currentDestination) {
                        TopLevelDestination.Home,
                        TopLevelDestination.Discover -> {
                            StandardTopAppBar(
                                title = { CrispyWordmark() },
                                actions = {
                                    ProfileIconButton(
                                        onClick = { navController.navigate(AppRoutes.AccountsProfilesRoute) }
                                    )
                                },
                                scrollBehavior = topAppBarScrollBehavior,
                            )
                        }

                        TopLevelDestination.Library -> {
                            StandardTopAppBar(
                                title = { CrispyWordmark() },
                                actions = {
                                    IconButton(onClick = { navController.navigate(AppRoutes.CalendarRoute) }) {
                                        Icon(
                                            imageVector = Icons.Outlined.Event,
                                            contentDescription = "Calendar",
                                        )
                                    }
                                    ProfileIconButton(
                                        onClick = { navController.navigate(AppRoutes.AccountsProfilesRoute) }
                                    )
                                },
                                scrollBehavior = topAppBarScrollBehavior,
                            )
                        }

                        TopLevelDestination.Settings -> {
                            StandardTopAppBar(
                                title = "Settings",
                                scrollBehavior = topAppBarScrollBehavior,
                            )
                        }

                        else -> Unit
                    }
                }
            },
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        bottomBarDestinations.forEach { destination ->
                            val selected = currentRoute == destination.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    if (selected) {
                                        val currentEntry = navController.currentBackStackEntry
                                        val currentRequest =
                                            currentEntry?.savedStateHandle?.get<Int>(AppRoutes.TopLevelScrollToTopRequestKey) ?: 0
                                        currentEntry?.savedStateHandle?.set(
                                            AppRoutes.TopLevelScrollToTopRequestKey,
                                            currentRequest + 1,
                                        )
                                        coroutineScope.launch {
                                            topAppBarScrollBehavior.state.resetHeightOffset()
                                        }
                                    } else {
                                        navController.navigate(destination.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = destination.icon,
                                        contentDescription = destination.label,
                                    )
                                },
                                label = { Text(destination.label) },
                            )
                        }
                    }
                }
            },
        ) {
            AppNavHost(
                navController = navController,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

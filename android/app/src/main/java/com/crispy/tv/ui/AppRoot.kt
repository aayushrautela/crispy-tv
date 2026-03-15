package com.crispy.tv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.crispy.tv.search.SearchTopBar
import com.crispy.tv.search.rememberSearchViewModel
import com.crispy.tv.ui.brand.CrispyMark
import com.crispy.tv.ui.brand.CrispyWordmark
import com.crispy.tv.ui.components.ProfileIconButton
import com.crispy.tv.ui.navigation.AppNavHost
import com.crispy.tv.ui.navigation.AppNavigationBar
import com.crispy.tv.ui.navigation.AppNavigationRail
import com.crispy.tv.ui.navigation.AppRoutes
import com.crispy.tv.ui.navigation.TopLevelDestination
import com.crispy.tv.ui.navigation.isRouteSelected
import com.crispy.tv.ui.utils.appBarScrollBehavior

private val NavigationRailWidth = 80.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val configuration = LocalConfiguration.current
    val topLevelDestinations = remember { TopLevelDestination.entries }
    val topLevelRoutes = remember(topLevelDestinations) { topLevelDestinations.map { it.route }.toSet() }
    val currentRoute by remember(navBackStackEntry) {
        derivedStateOf { navBackStackEntry?.destination?.route }
    }
    val currentDestination by remember(currentRoute, topLevelDestinations) {
        derivedStateOf {
            topLevelDestinations.firstOrNull { destination ->
                isRouteSelected(
                    currentRoute = currentRoute,
                    screenRoute = destination.route,
                    topLevelRoutes = topLevelRoutes,
                )
            }
        }
    }
    val shouldShowNavigationBar by remember(currentRoute, topLevelRoutes) {
        derivedStateOf {
            currentRoute == null ||
                topLevelRoutes.contains(currentRoute)
        }
    }
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val showRail = shouldShowNavigationBar && isLandscape
    val shouldShowTopBar by remember(currentRoute, currentDestination) {
        derivedStateOf {
            currentDestination?.showInRootTopBar == true && currentRoute == currentDestination?.route
        }
    }
    val bottomSystemInset = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
    val shouldUseScrollableTopBar by remember(currentDestination, shouldShowTopBar) {
        derivedStateOf {
            shouldShowTopBar && currentDestination != TopLevelDestination.Search
        }
    }
    val topAppBarScrollBehavior = appBarScrollBehavior(canScroll = { shouldUseScrollableTopBar })
    val searchViewModelStoreOwner =
        remember(currentRoute, navController) {
            if (currentRoute == AppRoutes.SearchRoute) {
                navController.getBackStackEntry(AppRoutes.SearchRoute)
            } else {
                null
            }
        }

    LaunchedEffect(currentRoute) {
        topAppBarScrollBehavior.state.heightOffset = 0f
        topAppBarScrollBehavior.state.contentOffset = 0f
    }

    val onTopLevelDestinationClick = remember(navController, topAppBarScrollBehavior) {
        { destination: TopLevelDestination, isSelected: Boolean ->
            if (isSelected) {
                val currentEntry = navController.currentBackStackEntry
                val currentRequest =
                    currentEntry
                        ?.savedStateHandle
                        ?.get<Int>(AppRoutes.TopLevelScrollToTopRequestKey)
                        ?: 0
                currentEntry?.savedStateHandle?.set(
                    AppRoutes.TopLevelScrollToTopRequestKey,
                    currentRequest + 1,
                )
                topAppBarScrollBehavior.state.heightOffset = 0f
                topAppBarScrollBehavior.state.contentOffset = 0f
            } else {
                navController.navigate(destination.route) {
                    popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = if (showRail) bottomSystemInset else 0.dp)
            .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            AnimatedVisibility(
                visible = shouldShowTopBar,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                RootTopAppBar(
                    currentDestination = currentDestination,
                    showRail = showRail,
                    scrollBehavior = topAppBarScrollBehavior,
                    searchViewModelStoreOwner = searchViewModelStoreOwner,
                    onOpenCalendar = {
                        navController.navigate(AppRoutes.CalendarRoute) {
                            launchSingleTop = true
                        }
                    },
                    onOpenAccountsProfiles = {
                        navController.navigate(AppRoutes.AccountsProfilesRoute) {
                            launchSingleTop = true
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (shouldShowNavigationBar && !showRail) {
                AppNavigationBar(
                    navigationItems = topLevelDestinations,
                    currentRoute = currentRoute,
                    onItemClick = onTopLevelDestinationClick,
                )
            }
        },
    ) { paddingValues ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues),
        ) {
            if (showRail) {
                AppNavigationRail(
                    navigationItems = topLevelDestinations,
                    currentRoute = currentRoute,
                    onItemClick = onTopLevelDestinationClick,
                )
            }
            AppNavHost(
                navController = navController,
                modifier = Modifier.weight(1f).fillMaxSize(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RootTopAppBar(
    currentDestination: TopLevelDestination?,
    showRail: Boolean,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    searchViewModelStoreOwner: ViewModelStoreOwner?,
    onOpenCalendar: () -> Unit,
    onOpenAccountsProfiles: () -> Unit,
) {
    if (currentDestination == null) {
        return
    }

    if (currentDestination == TopLevelDestination.Search && searchViewModelStoreOwner != null) {
        val searchViewModel = rememberSearchViewModel(viewModelStoreOwner = searchViewModelStoreOwner)
        val searchUiState by searchViewModel.uiState.collectAsStateWithLifecycle()
        SearchTopBar(
            query = searchUiState.query,
            onQueryChange = searchViewModel::updateQuery,
            onSearch = searchViewModel::submitSearch,
            onClear = searchViewModel::clearSearch,
            onOpenAccountsProfiles = onOpenAccountsProfiles,
            modifier = Modifier.padding(start = if (showRail) NavigationRailWidth else 0.dp),
        )
        return
    }

    TopAppBar(
        title = {
            when (currentDestination) {
                TopLevelDestination.Home -> {
                    CrispyWordmark(
                        modifier = Modifier
                            .width(105.dp)
                            .height(28.dp),
                    )
                }

                TopLevelDestination.Discover,
                TopLevelDestination.Library -> {
                    CrispyMark(
                        modifier = Modifier
                            .width(23.dp)
                            .height(28.dp),
                    )
                }

                else -> {
                    Text(
                        text = currentDestination.label,
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
        },
        actions = {
            when (currentDestination) {
                TopLevelDestination.Library -> {
                    IconButton(onClick = onOpenCalendar) {
                        Icon(
                            imageVector = Icons.Outlined.Event,
                            contentDescription = "Calendar",
                        )
                    }
                    ProfileIconButton(onClick = onOpenAccountsProfiles)
                }

                TopLevelDestination.Settings -> Unit
                else -> ProfileIconButton(onClick = onOpenAccountsProfiles)
            }
        },
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier.padding(start = if (showRail) NavigationRailWidth else 0.dp),
    )
}

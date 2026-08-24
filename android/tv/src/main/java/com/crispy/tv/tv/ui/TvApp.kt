package com.crispy.tv.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import android.app.Application
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.crispy.tv.tv.home.HomeViewModel
import com.crispy.tv.tv.session.TvSessionState
import com.crispy.tv.tv.session.TvSessionViewModel
import com.crispy.tv.tv.ui.components.SidebarNavigation
import com.crispy.tv.tv.ui.navigation.TvDestination
import com.crispy.tv.tv.ui.screens.HomeScreen
import com.crispy.tv.tv.ui.screens.detail.DetailScreen
import com.crispy.tv.tv.ui.screens.detail.DetailViewModel
import com.crispy.tv.tv.ui.screens.LibraryScreen
import com.crispy.tv.tv.ui.screens.SearchScreen
import com.crispy.tv.tv.ui.screens.SettingsScreen
import com.crispy.tv.tv.ui.screens.auth.ProfilePickerScreen
import com.crispy.tv.tv.ui.screens.auth.SignInScreen

@Composable
fun TvApp(sessionViewModel: TvSessionViewModel = viewModel()) {
    val session by sessionViewModel.state.collectAsStateWithLifecycle()

    when (val s = session) {
        TvSessionState.Loading -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Loading…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is TvSessionState.SignedOut -> {
            val signInInFlight by sessionViewModel.signInInFlight.collectAsStateWithLifecycle()
            val signInError by sessionViewModel.signInError.collectAsStateWithLifecycle()
            SignInScreen(
                configError = s.configError,
                inFlight = signInInFlight,
                error = signInError,
                onSignIn = sessionViewModel::signIn,
            )
        }
        is TvSessionState.NeedsProfile -> {
            ProfilePickerScreen(
                profiles = s.profiles,
                onSelect = sessionViewModel::selectProfile,
            )
        }
        is TvSessionState.SignedIn -> {
            SignedInApp(
                sessionViewModel = sessionViewModel,
            )
        }
    }
}

@Composable
private fun SignedInApp(sessionViewModel: TvSessionViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val selected = TvDestination.fromRoute(backStackEntry?.destination?.route)
    val homeViewModel: HomeViewModel = viewModel()

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(color = MaterialTheme.colorScheme.background),
    ) {
        SidebarNavigation(
            selected = selected,
            onSelect = { destination ->
                if (destination != selected) {
                    navController.navigate(destination.route) {
                        popUpTo(TvDestination.default.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            },
        )
        NavHost(
            navController = navController,
            startDestination = TvDestination.default.route,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(TvDestination.Home.route) {
                HomeScreen(
                    viewModel = homeViewModel,
                    onOpenItem = { itemId -> navController.navigate("detail/$itemId") },
                )
            }
            composable("detail/{itemId}") { entry ->
                val itemId = entry.arguments?.getString("itemId").orEmpty()
                val app = LocalContext.current.applicationContext as Application
                val detailViewModel: DetailViewModel = viewModel(
                    key = "detail-$itemId",
                    initializer = { DetailViewModel(app, itemId) },
                )
                DetailScreen(
                    state = detailViewModel.state.collectAsStateWithLifecycle().value,
                    onSelectSeason = detailViewModel::selectSeason,
                    onOpenItem = { nextId -> navController.navigate("detail/$nextId") },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(TvDestination.Search.route) { SearchScreen() }
            composable(TvDestination.Library.route) { LibraryScreen() }
            composable(TvDestination.Settings.route) { SettingsScreen() }
        }
    }
}

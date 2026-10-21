package com.crispy.tv.tv.ui

import androidx.activity.compose.BackHandler
import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.MaterialTheme
import com.crispy.tv.ui.brand.CrispyIntroSplash
import kotlinx.coroutines.delay
import com.crispy.tv.tv.player.TvPlayerViewModel
import com.crispy.tv.tv.sources.TvSourcesViewModel
import com.crispy.tv.tv.di.TvServices
import com.crispy.tv.tv.home.TvHomeViewModel
import com.crispy.tv.tv.session.TvSessionState
import com.crispy.tv.tv.session.TvSessionViewModel
import com.crispy.tv.tv.ui.components.LocalTvContentFocusRequester
import com.crispy.tv.tv.ui.components.SidebarNavigation
import com.crispy.tv.tv.ui.components.rememberTvSidebarState
import com.crispy.tv.tv.ui.navigation.TvDestination
import com.crispy.tv.tv.ui.screens.HomeScreen
import com.crispy.tv.tv.ui.screens.detail.DetailScreen
import com.crispy.tv.tv.ui.screens.detail.DetailViewModel
import com.crispy.tv.tv.ui.screens.LibraryScreen
import com.crispy.tv.tv.ui.screens.SearchScreen
import com.crispy.tv.tv.ui.screens.SettingsScreen
import com.crispy.tv.tv.ui.screens.auth.ProfilePickerScreen
import com.crispy.tv.tv.ui.screens.auth.SignInScreen
import com.crispy.tv.tv.ui.screens.player.TvPlayerScreen
import com.crispy.tv.tv.ui.sources.TvSourcesScreen

private const val IntroTimeoutMs = 3_000L

@Composable
fun TvApp(sessionViewModel: TvSessionViewModel = viewModel()) {
    val session by sessionViewModel.state.collectAsStateWithLifecycle()
    var introDone by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(IntroTimeoutMs)
        introDone = true
    }

    // Hold the splash until the intro has played through even if session
    // resolution finishes first; afterwards the finished frame holds.
    when {
        session == TvSessionState.Loading || !introDone -> {
            CrispyIntroSplash(
                playIntro = !introDone,
                onFinished = { introDone = true },
            )
        }
        session is TvSessionState.SignedOut -> {
            val signInInFlight by sessionViewModel.signInInFlight.collectAsStateWithLifecycle()
            val signInError by sessionViewModel.signInError.collectAsStateWithLifecycle()
            val deviceLogin by sessionViewModel.deviceLoginState.collectAsStateWithLifecycle()
            val configError = (session as? TvSessionState.SignedOut)?.configError
            LaunchedEffect(Unit) {
                sessionViewModel.startDeviceLogin()
            }
            SignInScreen(
                configError = configError ?: false,
                inFlight = signInInFlight,
                error = signInError,
                onSignIn = sessionViewModel::signIn,
                deviceLogin = deviceLogin,
                deviceLoginAvailable = sessionViewModel.isDeviceLoginAvailable,
                onStartDeviceLogin = sessionViewModel::startDeviceLogin,
                onCancelDeviceLogin = sessionViewModel::cancelDeviceLogin,
            )
        }
        session is TvSessionState.NeedsProfile -> {
            ProfilePickerScreen(
                profiles = (session as? TvSessionState.NeedsProfile)?.profiles.orEmpty(),
                onSelect = sessionViewModel::selectProfile,
            )
        }
        session is TvSessionState.SignedIn -> {
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
    val currentRoute = backStackEntry?.destination?.route
    val selected = TvDestination.fromRoute(currentRoute)
    val rootRoutes = remember { TvDestination.entries.map { it.route }.toSet() }
    val showSidebar = currentRoute in rootRoutes
    val sidebarState = rememberTvSidebarState()
    val contentFocusRequester = remember { FocusRequester() }

    val appContext = LocalContext.current.applicationContext
    val homeViewModel: TvHomeViewModel = viewModel(
        factory = TvHomeViewModel.factory(appContext),
    )
    LaunchedEffect(homeViewModel) {
        homeViewModel.ensureLoaded()
    }

    // Back on a root route opens the sidebar; a second Back exits the app.
    BackHandler(enabled = showSidebar && !sidebarState.isExpanded) {
        sidebarState.requestOpen()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color = MaterialTheme.colorScheme.background),
    ) {
        CompositionLocalProvider(LocalTvContentFocusRequester provides contentFocusRequester) {
            NavHost(
                navController = navController,
                startDestination = TvDestination.default.route,
                modifier = Modifier.fillMaxSize(),
            ) {
            composable(TvDestination.Home.route) {
                HomeScreen(
                    viewModel = homeViewModel,
                    onOpenItem = { itemId -> navController.navigate("detail/$itemId") },
                    // "Play" from home knows an item id and nothing else: the picker
                    // works out the lookup from it, and the pick goes to the player
                    // from there.
                    onPlayItem = { itemId -> navController.navigate("sources/$itemId") },
                )
            }
            composable(
                "sources/{itemId}?mediaType={mediaType}&lookupId={lookupId}",
                arguments = listOf(
                    navArgument("itemId") { type = NavType.StringType },
                    navArgument("mediaType") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("lookupId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                val itemIdArg = entry.arguments?.getString("itemId").orEmpty()
                val mediaTypeArg = entry.arguments?.getString("mediaType")
                val lookupIdArg = entry.arguments?.getString("lookupId")
                val appCtx = LocalContext.current.applicationContext as Application
                val sourcesViewModel: TvSourcesViewModel = viewModel(
                    key = "sources-$itemIdArg-$mediaTypeArg-$lookupIdArg",
                    initializer = { TvSourcesViewModel(appCtx, itemIdArg, mediaTypeArg, lookupIdArg) },
                )
                val sourcesState by sourcesViewModel.state.collectAsStateWithLifecycle()
                TvSourcesScreen(
                    state = sourcesState,
                    onPick = { row ->
                        // The pick is stored in process and named by an id: the route
                        // carries the id, not the URL, because a route cannot carry a
                        // provider's headers or the stream's subtitles.
                        val selection = sourcesViewModel.onSourcePicked(row)
                        if (selection != null) {
                            val selId = TvServices.playbackSelectionStore(appCtx).put(selection)
                            navController.navigate("play/$itemIdArg?sel=$selId")
                        }
                    },
                )
            }
            composable(
                "play/{itemId}?sel={sel}",
                arguments = listOf(
                    navArgument("itemId") { type = NavType.StringType },
                    navArgument("sel") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                val itemId = entry.arguments?.getString("itemId").orEmpty()
                val selId = entry.arguments?.getString("sel")
                val app = LocalContext.current.applicationContext as Application
                val playerViewModel: TvPlayerViewModel = viewModel(
                    key = "play-$itemId-$selId",
                    initializer = {
                        // The pick is taken, not read: it is consumed by the one
                        // player that plays it. A player restored after a process
                        // death finds the table empty and says so plainly.
                        TvPlayerViewModel(app, itemId, TvServices.playbackSelectionStore(app).take(selId))
                    },
                )
                val playerState by playerViewModel.state.collectAsStateWithLifecycle()
                TvPlayerScreen(
                    viewModel = playerViewModel,
                    onChangeSource = {
                        // The old player is gone and the picker is where it was: back
                        // from the next player lands on the picker, where "change
                        // source" started. The lookup travels with it, so the picker
                        // asks for the same episode rather than working it out again.
                        val query = buildList {
                            playerState.lookupMediaTypeName?.takeIf { it.isNotBlank() }
                                ?.let { add("mediaType=" + android.net.Uri.encode(it)) }
                            playerState.lookupId?.takeIf { it.isNotBlank() }
                                ?.let { add("lookupId=" + android.net.Uri.encode(it)) }
                        }.joinToString("&")
                        val route = if (query.isEmpty()) "sources/$itemId" else "sources/$itemId?$query"
                        navController.navigate(route) {
                            popUpTo(TvDestination.default.route)
                        }
                    },
                )
            }
            composable("detail/{itemId}") { entry ->
                val itemId = entry.arguments?.getString("itemId").orEmpty()
                val app = LocalContext.current.applicationContext as Application
                val detailViewModel: DetailViewModel = viewModel(
                    key = "detail-$itemId",
                    initializer = { DetailViewModel(app, itemId) },
                )
                val detailState by detailViewModel.state.collectAsStateWithLifecycle()
                DetailScreen(
                    state = detailState,
                    onSelectSeason = detailViewModel::selectSeason,
                    onOpenItem = { nextId -> navController.navigate("detail/$nextId") },
                    onBack = { navController.popBackStack() },
                    onToggleWatchlist = detailViewModel::toggleWatchlist,
                    onToggleWatched = detailViewModel::toggleWatched,
                    onToggleEpisodeWatched = detailViewModel::toggleEpisodeWatched,
                    onAiInsightsClick = detailViewModel::onAiInsightsClick,
                    onDismissAiInsights = detailViewModel::dismissAiInsightsStory,
                    onSetLiked = detailViewModel::setLiked,
                    onPlay = { id ->
                        // The detail screen knows the lookup it wants; the picker
                        // takes it from there, and the pick goes to the player.
                        val target = detailState
                        val query = buildList {
                            target.lookupMediaTypeName?.takeIf { it.isNotBlank() }
                                ?.let { add("mediaType=" + android.net.Uri.encode(it)) }
                            target.lookupId?.takeIf { it.isNotBlank() }
                                ?.let { add("lookupId=" + android.net.Uri.encode(it)) }
                        }.joinToString("&")
                        navController.navigate(if (query.isEmpty()) "sources/$id" else "sources/$id?$query")
                    },
                )
            }
                composable(TvDestination.Search.route) { SearchScreen() }
                composable(TvDestination.Library.route) { LibraryScreen() }
                composable(TvDestination.Settings.route) { SettingsScreen() }
            }
        }

        if (showSidebar) {
            SidebarNavigation(
                state = sidebarState,
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
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
    }
}

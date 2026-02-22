package com.crispy.rewrite

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Log
import android.view.ViewGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import com.crispy.rewrite.introskip.IntroSkipButtonOverlay
import com.crispy.rewrite.introskip.IntroSkipInterval
import com.crispy.rewrite.introskip.IntroSkipRequest
import com.crispy.rewrite.home.HomeScreen
import com.crispy.rewrite.details.DetailsRoute
import com.crispy.rewrite.settings.AddonsSettingsRoute
import com.crispy.rewrite.settings.HomeScreenSettingsRoute
import com.crispy.rewrite.settings.HomeScreenSettingsStore
import com.crispy.rewrite.settings.PlaybackSettingsRepositoryProvider
import com.crispy.rewrite.settings.PlaybackSettingsScreen
import com.crispy.rewrite.settings.ProviderAuthPortalRoute
import com.crispy.rewrite.settings.SettingsScreen
import com.crispy.rewrite.search.SearchRoute
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Surface
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.PlayerView
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil.compose.AsyncImage
import com.crispy.rewrite.catalog.CatalogItem
import com.crispy.rewrite.catalog.CatalogRoute
import com.crispy.rewrite.catalog.CatalogSectionRef
import com.crispy.rewrite.discover.DiscoverRoute
import com.crispy.rewrite.nativeengine.playback.NativePlaybackEngine
import com.crispy.rewrite.nativeengine.playback.NativePlaybackEvent
import com.crispy.rewrite.library.LibraryRoute
import com.crispy.rewrite.player.MetadataLabMediaType
import com.crispy.rewrite.player.PlaybackEngine
import com.crispy.rewrite.player.PlaybackLabViewModel
import com.crispy.rewrite.player.WatchProvider
import com.crispy.rewrite.sync.ProviderSyncScheduler
import com.crispy.rewrite.ui.theme.CrispyRewriteTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val oauthWatchHistoryService by lazy(LazyThreadSafetyMode.NONE) {
        PlaybackLabDependencies.watchHistoryServiceFactory(applicationContext)
    }
    private val homeScreenSettingsStore by lazy(LazyThreadSafetyMode.NONE) {
        HomeScreenSettingsStore(applicationContext)
    }

    private var lastHandledOAuthCallback: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        ProviderSyncScheduler.ensureScheduled(applicationContext)
        ProviderSyncScheduler.enqueueNow(applicationContext)

        consumeOAuthCallback(intent)
        setContent {
            CrispyRewriteTheme {
                val isDark = isSystemInDarkTheme()
                DisposableEffect(isDark) {
                    window.statusBarColor = android.graphics.Color.TRANSPARENT
                    window.navigationBarColor = android.graphics.Color.TRANSPARENT
                    if (Build.VERSION.SDK_INT >= 29) {
                        window.isStatusBarContrastEnforced = false
                        window.isNavigationBarContrastEnforced = false
                    }
                    val controller = WindowCompat.getInsetsController(window, window.decorView)
                    controller.isAppearanceLightStatusBars = !isDark
                    controller.isAppearanceLightNavigationBars = !isDark
                    onDispose { }
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppShell()
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeOAuthCallback(intent)
    }

    private fun consumeOAuthCallback(intent: android.content.Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "crispy") {
            return
        }
        if (data.host != "auth") {
            return
        }
        val path = data.path.orEmpty()
        if (!path.startsWith("/trakt") && !path.startsWith("/simkl")) {
            return
        }

        // Prevent accidental re-handling if this intent is reused.
        runCatching { intent.data = null }

        val callback = data.toString()
        if (callback == lastHandledOAuthCallback) {
            return
        }
        lastHandledOAuthCallback = callback

        Log.d(TAG, "Received provider OAuth callback (${oauthUriSummaryForLog(data)})")

        lifecycleScope.launch(Dispatchers.IO) {
            val result =
                when {
                    path.startsWith("/trakt") -> oauthWatchHistoryService.completeTraktOAuth(callback)
                    path.startsWith("/simkl") -> oauthWatchHistoryService.completeSimklOAuth(callback)
                    else -> null
                }

            if (result == null) {
                Log.w(TAG, "Provider OAuth callback ignored (unrecognized path=$path)")
            } else {
                Log.i(TAG, "Provider OAuth completion (success=${result.success} message=${result.statusMessage})")
                if (result.success) {
                    val provider =
                        when {
                            path.startsWith("/trakt") -> WatchProvider.TRAKT
                            path.startsWith("/simkl") -> WatchProvider.SIMKL
                            else -> null
                        }
                    if (provider != null) {
                        val preferences = homeScreenSettingsStore.load()
                        homeScreenSettingsStore.save(preferences.copy(watchDataSource = provider))
                        ProviderSyncScheduler.enqueueNow(applicationContext)
                    }
                }
            }
        }
    }

    private fun oauthUriSummaryForLog(uri: Uri): String {
        val statePresent = !uri.getQueryParameter("state").isNullOrBlank()
        val codePresent = !uri.getQueryParameter("code").isNullOrBlank()
        val errorPresent = !uri.getQueryParameter("error").isNullOrBlank()
        return "scheme=${uri.scheme.orEmpty()}, host=${uri.host.orEmpty()}, path=${uri.path.orEmpty()}, statePresent=$statePresent, codePresent=$codePresent, errorPresent=$errorPresent"
    }

    companion object {
        private const val TAG = "ProviderOAuth"
    }
}

private enum class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val showInBottomBar: Boolean = true
) {
    Home(route = "home", label = "Home", icon = Icons.Outlined.Home),
    Search(route = "search", label = "Search", icon = Icons.Outlined.Search),
    Discover(route = "discover", label = "Discover", icon = Icons.Outlined.Explore),
    Library(route = "library", label = "Library", icon = Icons.Outlined.VideoLibrary),
    Settings(route = "settings", label = "Settings", icon = Icons.Outlined.Settings)
}

private const val HomeDetailsRoute = "home/details"
private const val HomeDetailsItemIdArg = "itemId"
private const val LabsRoute = "labs"
private const val PlaybackSettingsRoute = "settings/playback"
private const val HomeScreenSettingsRoutePath = "settings/home"
private const val AddonsSettingsRoutePath = "settings/addons"
private const val ProviderPortalRoutePath = "settings/providers"

private const val CatalogListRoute = "catalog"
private const val CatalogMediaTypeArg = "mediaType"
private const val CatalogIdArg = "catalogId"
private const val CatalogTitleArg = "title"
private const val CatalogAddonIdArg = "addonId"
private const val CatalogBaseUrlArg = "baseUrl"
private const val CatalogQueryArg = "query"

private fun homeDetailsRoute(itemId: String): String = "$HomeDetailsRoute/${Uri.encode(itemId)}"

private fun catalogListRoute(section: CatalogSectionRef): String {
    val query = section.encodedAddonQuery ?: ""
    return "$CatalogListRoute/${Uri.encode(section.mediaType)}/${Uri.encode(section.catalogId)}" +
        "?$CatalogTitleArg=${Uri.encode(section.title)}" +
        "&$CatalogAddonIdArg=${Uri.encode(section.addonId)}" +
        "&$CatalogBaseUrlArg=${Uri.encode(section.baseUrl)}" +
        "&$CatalogQueryArg=${Uri.encode(query)}"
}

@Composable
private fun AppShell() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val bottomBarDestinations = remember { TopLevelDestination.entries.filter { it.showInBottomBar } }
    val showBottomBar = bottomBarDestinations.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomBarDestinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = destination.label
                                )
                            },
                            label = { Text(destination.label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.Home.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            composable(TopLevelDestination.Home.route) {
                HomeScreen(
                    onHeroClick = { hero ->
                        navController.navigate(homeDetailsRoute(hero.id))
                    },
                    onContinueWatchingClick = { item ->
                        navController.navigate(homeDetailsRoute(item.contentId))
                    },
                    onCatalogItemClick = { catalogItem ->
                        navController.navigate(homeDetailsRoute(catalogItem.id))
                    },
                    onCatalogSeeAllClick = { section ->
                        navController.navigate(catalogListRoute(section))
                    }
                )
            }

            composable(TopLevelDestination.Search.route) {
                SearchRoute(
                    onItemClick = { item -> navController.navigate(homeDetailsRoute(item.id)) }
                )
            }

            composable(
                route =
                    "$CatalogListRoute/{$CatalogMediaTypeArg}/{$CatalogIdArg}" +
                        "?$CatalogTitleArg={$CatalogTitleArg}" +
                        "&$CatalogAddonIdArg={$CatalogAddonIdArg}" +
                        "&$CatalogBaseUrlArg={$CatalogBaseUrlArg}" +
                        "&$CatalogQueryArg={$CatalogQueryArg}",
                arguments =
                    listOf(
                        navArgument(CatalogMediaTypeArg) { type = NavType.StringType },
                        navArgument(CatalogIdArg) { type = NavType.StringType },
                        navArgument(CatalogTitleArg) { type = NavType.StringType; defaultValue = "" },
                        navArgument(CatalogAddonIdArg) { type = NavType.StringType; defaultValue = "" },
                        navArgument(CatalogBaseUrlArg) { type = NavType.StringType; defaultValue = "" },
                        navArgument(CatalogQueryArg) { type = NavType.StringType; defaultValue = "" }
                )
            ) { entry ->
                val args = entry.arguments
                val section =
                    CatalogSectionRef(
                        title = args?.getString(CatalogTitleArg).orEmpty(),
                        catalogId = args?.getString(CatalogIdArg).orEmpty(),
                        mediaType = args?.getString(CatalogMediaTypeArg).orEmpty(),
                        addonId = args?.getString(CatalogAddonIdArg).orEmpty(),
                        baseUrl = args?.getString(CatalogBaseUrlArg).orEmpty(),
                        encodedAddonQuery = args?.getString(CatalogQueryArg)?.takeIf { it.isNotBlank() }
                    )
                CatalogRoute(
                    section = section,
                    onBack = { navController.popBackStack() },
                    onItemClick = { item -> navController.navigate(homeDetailsRoute(item.id)) }
                )
            }
            composable(
                route = "$HomeDetailsRoute/{$HomeDetailsItemIdArg}",
                arguments = listOf(navArgument(HomeDetailsItemIdArg) { type = NavType.StringType })
            ) { entry ->
                val itemId = entry.arguments?.getString(HomeDetailsItemIdArg).orEmpty()
                DetailsRoute(itemId = itemId, onBack = { navController.popBackStack() })
            }
            composable(TopLevelDestination.Discover.route) {
                DiscoverRoute(
                    onItemClick = { item -> navController.navigate(homeDetailsRoute(item.id)) }
                )
            }
            composable(TopLevelDestination.Library.route) {
                LibraryRoute(
                    onItemClick = { entry -> navController.navigate(homeDetailsRoute(entry.contentId)) },
                    onNavigateToDiscover = { navController.navigate(TopLevelDestination.Discover.route) }
                )
            }
            composable(TopLevelDestination.Settings.route) {
                SettingsScreen(
                    onNavigateToHomeScreenSettings = {
                        navController.navigate(HomeScreenSettingsRoutePath)
                    },
                    onNavigateToAddonsSettings = {
                        navController.navigate(AddonsSettingsRoutePath)
                    },
                    onNavigateToPlaybackSettings = {
                        navController.navigate(PlaybackSettingsRoute)
                    },
                    onNavigateToLabs = {
                        navController.navigate(LabsRoute)
                    },
                    onNavigateToProviderPortal = {
                        navController.navigate(ProviderPortalRoutePath)
                    }
                )
            }
            composable(HomeScreenSettingsRoutePath) {
                HomeScreenSettingsRoute(onBack = { navController.popBackStack() })
            }
            composable(AddonsSettingsRoutePath) {
                AddonsSettingsRoute(onBack = { navController.popBackStack() })
            }
            composable(ProviderPortalRoutePath) {
                ProviderAuthPortalRoute(onBack = { navController.popBackStack() })
            }
            composable(PlaybackSettingsRoute) {
                val context = LocalContext.current
                val appContext = remember(context) { context.applicationContext }
                val playbackSettingsRepository = remember(appContext) {
                    PlaybackSettingsRepositoryProvider.get(appContext)
                }
                val playbackSettings by playbackSettingsRepository.settings.collectAsStateWithLifecycle()

                PlaybackSettingsScreen(
                    settings = playbackSettings,
                    onSkipIntroChanged = playbackSettingsRepository::setSkipIntroEnabled,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(LabsRoute) {
                LabsScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabsScreen() {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }

    val metadataResolver = remember(appContext) {
        PlaybackLabDependencies.metadataResolverFactory(appContext)
    }
    val catalogSearchService = remember(appContext) {
        PlaybackLabDependencies.catalogSearchServiceFactory(appContext)
    }
    val watchHistoryService = remember(appContext) {
        PlaybackLabDependencies.watchHistoryServiceFactory(appContext)
    }
    val supabaseSyncService = remember(appContext, watchHistoryService) {
        PlaybackLabDependencies.supabaseSyncServiceFactory(appContext, watchHistoryService)
    }
    val viewModel: PlaybackLabViewModel = viewModel(
        factory = remember(metadataResolver, catalogSearchService, watchHistoryService, supabaseSyncService) {
            PlaybackLabViewModel.factory(
                metadataResolver = metadataResolver,
                catalogSearchService = catalogSearchService,
                watchHistoryService = watchHistoryService,
                supabaseSyncService = supabaseSyncService
            )
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val playbackSettingsRepository = remember(appContext) {
        PlaybackSettingsRepositoryProvider.get(appContext)
    }
    val playbackSettings by playbackSettingsRepository.settings.collectAsStateWithLifecycle()
    val introSkipService = remember(appContext) {
        PlaybackLabDependencies.introSkipServiceFactory(appContext)
    }

    val playbackController = remember(context) {
        PlaybackLabDependencies.playbackControllerFactory(context) { event ->
            when (event) {
                NativePlaybackEvent.Buffering -> viewModel.onNativeBuffering()
                NativePlaybackEvent.Ready -> viewModel.onNativeReady()
                NativePlaybackEvent.Ended -> viewModel.onNativeEnded()
                is NativePlaybackEvent.Error -> {
                    if (event.codecLikely) {
                        viewModel.onNativeCodecError(event.message)
                    } else {
                        viewModel.onPlaybackLaunchFailed(event.message)
                    }
                }
            }
        }
    }

    val torrentResolver = remember(context) {
        PlaybackLabDependencies.torrentResolverFactory(context)
    }

    var playerVisible by remember { mutableStateOf(false) }
    var introSkipImdbIdInput by rememberSaveable { mutableStateOf("") }
    var introSkipSeasonInput by rememberSaveable { mutableStateOf("") }
    var introSkipEpisodeInput by rememberSaveable { mutableStateOf("") }
    var introSkipMalIdInput by rememberSaveable { mutableStateOf("") }
    var introSkipKitsuIdInput by rememberSaveable { mutableStateOf("") }
    var introSkipIntervals by remember { mutableStateOf(emptyList<IntroSkipInterval>()) }
    var introSkipStatusMessage by remember { mutableStateOf("Provide episode identity to enable intro skip.") }
    var introSkipLoading by remember { mutableStateOf(false) }
    var playbackPositionMs by remember { mutableStateOf(0L) }

    val mergedImdbId = uiState.metadataResolution?.mergedImdbId?.toImdbIdOrNull()
    val effectiveIntroSkipImdbId = introSkipImdbIdInput.toImdbIdOrNull() ?: mergedImdbId
    val introSkipSeason = introSkipSeasonInput.toPositiveIntOrNull()
    val introSkipEpisode = introSkipEpisodeInput.toPositiveIntOrNull()
    val introSkipMalId = introSkipMalIdInput.toPositiveIntOrNull()
    val introSkipKitsuId = introSkipKitsuIdInput.toPositiveIntOrNull()

    val introSkipRequest = remember(
        effectiveIntroSkipImdbId,
        introSkipSeason,
        introSkipEpisode,
        introSkipMalId,
        introSkipKitsuId
    ) {
        val season = introSkipSeason ?: return@remember null
        val episode = introSkipEpisode ?: return@remember null

        IntroSkipRequest(
            imdbId = effectiveIntroSkipImdbId,
            season = season,
            episode = episode,
            malId = introSkipMalId,
            kitsuId = introSkipKitsuId
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            viewModel.onPlaybackLaunchFailed("Notification permission denied. Torrent service may fail on Android 13+.")
        }
    }

    DisposableEffect(playbackController, torrentResolver) {
        onDispose {
            torrentResolver.stopAndClear()
            torrentResolver.close()
            playbackController.release()
        }
    }

    LaunchedEffect(uiState.playbackRequestVersion) {
        val streamUrl = uiState.playbackUrl ?: return@LaunchedEffect

        runCatching {
            playbackController.play(
                url = streamUrl,
                engine = uiState.activeEngine.toNativeEngine()
            )
        }.onSuccess {
            playerVisible = true
        }.onFailure { error ->
            viewModel.onPlaybackLaunchFailed(error.message ?: "unknown playback launch error")
        }
    }

    LaunchedEffect(playerVisible, uiState.activeEngine, uiState.playbackRequestVersion) {
        if (!playerVisible) {
            playbackPositionMs = 0L
            return@LaunchedEffect
        }

        while (true) {
            playbackPositionMs = playbackController.currentPositionMs()
            delay(250L)
        }
    }

    LaunchedEffect(playbackSettings.skipIntroEnabled, introSkipRequest, uiState.playbackRequestVersion) {
        if (!playbackSettings.skipIntroEnabled) {
            introSkipLoading = false
            introSkipIntervals = emptyList()
            introSkipStatusMessage = "Intro skip is disabled in Playback settings."
            return@LaunchedEffect
        }

        val request = introSkipRequest
        if (request == null) {
            introSkipLoading = false
            introSkipIntervals = emptyList()
            introSkipStatusMessage = "Enter season and episode with at least one IMDb, MAL, or Kitsu ID."
            return@LaunchedEffect
        }

        introSkipLoading = true
        introSkipStatusMessage = "Loading intro skip segments..."

        runCatching {
            introSkipService.getSkipIntervals(request)
        }.onSuccess { intervals ->
            introSkipIntervals = intervals
            introSkipStatusMessage =
                if (intervals.isEmpty()) {
                    "No skip segments found for this episode."
                } else {
                    "Loaded ${intervals.size} skip segment${if (intervals.size == 1) "" else "s"}."
                }
        }.onFailure { error ->
            introSkipIntervals = emptyList()
            introSkipStatusMessage = "Intro skip lookup failed: ${error.message ?: "unknown error"}"
        }

        introSkipLoading = false
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Settings & Labs",
                style = MaterialTheme.typography.headlineSmall
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Playback Lab", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Material 3 shell with native engine orchestration. Exo is primary, VLC is fallback.",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier.testTag("engine_exo_button"),
                            enabled = uiState.activeEngine != PlaybackEngine.EXO,
                            onClick = { viewModel.onEngineSelected(PlaybackEngine.EXO) }
                        ) {
                            Text("Use Exo")
                        }

                        Button(
                            modifier = Modifier.testTag("engine_vlc_button"),
                            enabled = uiState.activeEngine != PlaybackEngine.VLC,
                            onClick = { viewModel.onEngineSelected(PlaybackEngine.VLC) }
                        ) {
                            Text("Use VLC")
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier.testTag("play_sample_button"),
                            enabled = !uiState.isPreparingTorrentPlayback,
                            onClick = {
                                viewModel.onPlaySampleRequested()
                            }
                        ) {
                            Text("Play Sample Video")
                        }
                    }

                    OutlinedTextField(
                        value = uiState.magnetInput,
                        onValueChange = viewModel::onMagnetChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("magnet_input"),
                        label = { Text("Torrent magnet URI") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )

                    Text(
                        text = "Intro Skip Identity",
                        style = MaterialTheme.typography.titleSmall
                    )
                    OutlinedTextField(
                        value = introSkipImdbIdInput,
                        onValueChange = { introSkipImdbIdInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("IMDb ID (tt1234567)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                    )

                    if (introSkipImdbIdInput.isBlank() && mergedImdbId != null) {
                        Text(
                            text = "Using resolved IMDb ID from Metadata Lab: $mergedImdbId",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    OutlinedTextField(
                        value = introSkipSeasonInput,
                        onValueChange = { introSkipSeasonInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Season") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    OutlinedTextField(
                        value = introSkipEpisodeInput,
                        onValueChange = { introSkipEpisodeInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Episode") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    OutlinedTextField(
                        value = introSkipMalIdInput,
                        onValueChange = { introSkipMalIdInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("MAL ID (optional)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    OutlinedTextField(
                        value = introSkipKitsuIdInput,
                        onValueChange = { introSkipKitsuIdInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Kitsu ID (optional)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    Text(
                        text =
                            if (introSkipLoading) {
                                "Loading intro skip segments..."
                            } else {
                                introSkipStatusMessage
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("intro_skip_status_text")
                    )

                    Button(
                        modifier = Modifier.testTag("start_torrent_button"),
                        enabled = !uiState.isPreparingTorrentPlayback,
                        onClick = {
                            if (
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                return@Button
                            }

                            val magnet = viewModel.onStartTorrentRequested() ?: return@Button

                            playerVisible = false
                            playbackController.stop()

                            scope.launch {
                                runCatching {
                                    torrentResolver.resolveStreamUrl(
                                        magnetLink = magnet,
                                        sessionId = "playback-lab"
                                    )
                                }.onSuccess { streamUrl ->
                                    viewModel.onTorrentStreamResolved(streamUrl)
                                }.onFailure { error ->
                                    viewModel.onTorrentPlaybackFailed(error.message ?: "unknown torrent error")
                                }
                            }
                        }
                    ) {
                        Text("Start Torrent")
                    }

                    Text(
                        modifier = Modifier.testTag("engine_phase_text"),
                        text = "Engine: ${uiState.activeEngine.name} | Phase: ${uiState.playerState.phase.name}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        modifier = Modifier.testTag("status_text"),
                        text = uiState.statusMessage,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text =
                            "Intro skip: ${if (playbackSettings.skipIntroEnabled) "enabled" else "disabled"} " +
                                "| segments=${introSkipIntervals.size} | position=${playbackPositionMs / 1000}s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (playerVisible) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp)
                        ) {
                            when (uiState.activeEngine) {
                                PlaybackEngine.EXO -> {
                                    ExoPlayerSurface(
                                        modifier = Modifier.fillMaxSize(),
                                        bind = playbackController::bindExoPlayerView
                                    )
                                }

                                PlaybackEngine.VLC -> {
                                    VlcPlayerSurface(
                                        modifier = Modifier.fillMaxSize(),
                                        create = playbackController::createVlcSurfaceView,
                                        attach = playbackController::attachVlcSurface
                                    )
                                }
                            }

                            IntroSkipButtonOverlay(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(12.dp),
                                enabled = playbackSettings.skipIntroEnabled,
                                currentPositionMs = playbackPositionMs,
                                intervals = introSkipIntervals,
                                onSkipRequested = playbackController::seekTo
                            )
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Metadata Lab (Phase 3.2)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Nuvio-style IDs + addon-first merge with TMDB enhancer bridge.",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier.testTag("metadata_movie_button"),
                            enabled = uiState.metadataMediaType != MetadataLabMediaType.MOVIE,
                            onClick = {
                                viewModel.onMetadataMediaTypeSelected(MetadataLabMediaType.MOVIE)
                            }
                        ) {
                            Text("Movie")
                        }

                        Button(
                            modifier = Modifier.testTag("metadata_series_button"),
                            enabled = uiState.metadataMediaType != MetadataLabMediaType.SERIES,
                            onClick = {
                                viewModel.onMetadataMediaTypeSelected(MetadataLabMediaType.SERIES)
                            }
                        ) {
                            Text("Series")
                        }
                    }

                    OutlinedTextField(
                        value = uiState.metadataInputId,
                        onValueChange = viewModel::onMetadataInputChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("metadata_id_input"),
                        label = { Text("Metadata ID") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                    )

                    OutlinedTextField(
                        value = uiState.metadataPreferredAddonId,
                        onValueChange = viewModel::onMetadataPreferredAddonChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("metadata_preferred_addon_input"),
                        label = { Text("Preferred addon (optional)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                    )

                    Button(
                        modifier = Modifier.testTag("resolve_metadata_button"),
                        enabled = !uiState.isResolvingMetadata,
                        onClick = {
                            viewModel.onResolveMetadataRequested()
                        }
                    ) {
                        Text(if (uiState.isResolvingMetadata) "Resolving..." else "Resolve Metadata")
                    }

                    Text(
                        modifier = Modifier.testTag("metadata_status_text"),
                        text = uiState.metadataStatusMessage,
                        style = MaterialTheme.typography.bodySmall
                    )

                    uiState.metadataResolution?.let { resolved ->
                        Text(
                            modifier = Modifier.testTag("metadata_normalized_text"),
                            text = "content_id=${resolved.contentId} | video_id=${resolved.videoId ?: "-"}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            modifier = Modifier.testTag("metadata_primary_text"),
                            text = "primary=${resolved.primaryId} | title=${resolved.primaryTitle}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            modifier = Modifier.testTag("metadata_sources_text"),
                            text = "sources=${resolved.sources.joinToString()}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            modifier = Modifier.testTag("metadata_bridge_text"),
                            text = "bridge=${resolved.bridgeCandidateIds.joinToString()}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            modifier = Modifier.testTag("metadata_enrichment_text"),
                            text = "needs_enrichment=${resolved.needsEnrichment} | imdb=${resolved.mergedImdbId ?: "-"}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        val transportText =
                            if (resolved.transportStats.isEmpty()) {
                                "transport=none"
                            } else {
                                "transport=" + resolved.transportStats.joinToString(separator = " | ") { stat ->
                                    "${stat.addonId}:stream=${stat.streamCount},sub=${stat.subtitleCount}"
                                }
                            }
                        Text(
                            modifier = Modifier.testTag("metadata_transport_text"),
                            text = transportText,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Catalog + Search Lab", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Registry-ordered addon catalogs with Nuvio URL fallback; search uses searchable catalogs only.",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier.testTag("catalog_movie_button"),
                            enabled = uiState.catalogMediaType != MetadataLabMediaType.MOVIE,
                            onClick = {
                                viewModel.onCatalogMediaTypeSelected(MetadataLabMediaType.MOVIE)
                            }
                        ) {
                            Text("Movie")
                        }

                        Button(
                            modifier = Modifier.testTag("catalog_series_button"),
                            enabled = uiState.catalogMediaType != MetadataLabMediaType.SERIES,
                            onClick = {
                                viewModel.onCatalogMediaTypeSelected(MetadataLabMediaType.SERIES)
                            }
                        ) {
                            Text("Series")
                        }
                    }

                    OutlinedTextField(
                        value = uiState.catalogInputId,
                        onValueChange = viewModel::onCatalogIdChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("catalog_id_input"),
                        label = { Text("Catalog ID (optional)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                    )

                    Text(
                        "Tip: leave Catalog ID empty and tap Load Catalog to list available addon catalogs.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    OutlinedTextField(
                        value = uiState.catalogSearchQuery,
                        onValueChange = viewModel::onCatalogSearchQueryChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("catalog_search_input"),
                        label = { Text("Search query") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                    )

                    OutlinedTextField(
                        value = uiState.catalogPreferredAddonId,
                        onValueChange = viewModel::onCatalogPreferredAddonChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("catalog_preferred_addon_input"),
                        label = { Text("Preferred addon (optional)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier.testTag("catalog_load_button"),
                            enabled = !uiState.isLoadingCatalog,
                            onClick = {
                                viewModel.onLoadCatalogRequested()
                            }
                        ) {
                            Text(if (uiState.isLoadingCatalog) "Loading..." else "Load Catalog")
                        }

                        Button(
                            modifier = Modifier.testTag("catalog_search_button"),
                            enabled = !uiState.isLoadingCatalog,
                            onClick = {
                                viewModel.onSearchCatalogRequested()
                            }
                        ) {
                            Text(if (uiState.isLoadingCatalog) "Searching..." else "Search")
                        }
                    }

                    Text(
                        modifier = Modifier.testTag("catalog_status_text"),
                        text = uiState.catalogStatusMessage,
                        style = MaterialTheme.typography.bodySmall
                    )

                    val catalogsText =
                        if (uiState.catalogAvailableCatalogs.isEmpty()) {
                            "catalogs=none"
                        } else {
                            "catalogs=" + uiState.catalogAvailableCatalogs.take(6).joinToString { catalog ->
                                val mode = if (catalog.supportsSearch) "search" else "browse"
                                "${catalog.name} [${catalog.catalogType}/${catalog.catalogId}] @${catalog.addonId} ($mode)"
                            }
                        }
                    Text(
                        modifier = Modifier.testTag("catalog_catalogs_text"),
                        text = catalogsText,
                        style = MaterialTheme.typography.bodySmall
                    )

                    val itemsText =
                        if (uiState.catalogItems.isEmpty()) {
                            "results=none"
                        } else {
                            "results=" + uiState.catalogItems.take(5).joinToString { item ->
                                "${item.id}:${item.title}"
                            }
                        }
                    Text(
                        modifier = Modifier.testTag("catalog_results_text"),
                        text = itemsText,
                        style = MaterialTheme.typography.bodySmall
                    )

                    val attemptText =
                        if (uiState.catalogAttemptedUrls.isEmpty()) {
                            "attempts=none"
                        } else {
                            "attempts=" + uiState.catalogAttemptedUrls.takeLast(3).joinToString(separator = " | ")
                        }
                    Text(
                        modifier = Modifier.testTag("catalog_attempts_text"),
                        text = attemptText,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Watch History + Sync", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Local watched ledger with optional Trakt/Simkl history sync.",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier.testTag("watch_movie_button"),
                            enabled = uiState.watchContentType != MetadataLabMediaType.MOVIE,
                            onClick = {
                                viewModel.onWatchContentTypeSelected(MetadataLabMediaType.MOVIE)
                            }
                        ) {
                            Text("Movie")
                        }

                        Button(
                            modifier = Modifier.testTag("watch_series_button"),
                            enabled = uiState.watchContentType != MetadataLabMediaType.SERIES,
                            onClick = {
                                viewModel.onWatchContentTypeSelected(MetadataLabMediaType.SERIES)
                            }
                        ) {
                            Text("Series")
                        }
                    }

                    OutlinedTextField(
                        value = uiState.watchContentId,
                        onValueChange = viewModel::onWatchContentIdChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("watch_content_id_input"),
                        label = { Text("Content ID") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                    )

                    OutlinedTextField(
                        value = uiState.watchRemoteImdbId,
                        onValueChange = viewModel::onWatchRemoteImdbIdChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("watch_remote_imdb_input"),
                        label = { Text("Remote IMDb ID (optional)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                    )

                    OutlinedTextField(
                        value = uiState.watchTitle,
                        onValueChange = viewModel::onWatchTitleChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("watch_title_input"),
                        label = { Text("Title (optional)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                    )

                    OutlinedTextField(
                        value = uiState.watchSeasonInput,
                        onValueChange = viewModel::onWatchSeasonChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("watch_season_input"),
                        label = { Text("Season (series only)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    OutlinedTextField(
                        value = uiState.watchEpisodeInput,
                        onValueChange = viewModel::onWatchEpisodeChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("watch_episode_input"),
                        label = { Text("Episode (series only)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    OutlinedTextField(
                        value = uiState.watchTraktToken,
                        onValueChange = viewModel::onWatchTraktTokenChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("watch_trakt_token_input"),
                        label = { Text("Trakt access token") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                    )

                    OutlinedTextField(
                        value = uiState.watchSimklToken,
                        onValueChange = viewModel::onWatchSimklTokenChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("watch_simkl_token_input"),
                        label = { Text("Simkl access token") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier.testTag("watch_save_tokens_button"),
                            enabled = !uiState.isUpdatingWatchHistory,
                            onClick = {
                                viewModel.onSaveWatchTokensRequested()
                            }
                        ) {
                            Text("Save Tokens")
                        }

                        Button(
                            modifier = Modifier.testTag("watch_refresh_button"),
                            enabled = !uiState.isUpdatingWatchHistory,
                            onClick = {
                                viewModel.onRefreshWatchHistoryRequested()
                            }
                        ) {
                            Text(if (uiState.isUpdatingWatchHistory) "Working..." else "Refresh")
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier.testTag("watch_mark_button"),
                            enabled = !uiState.isUpdatingWatchHistory,
                            onClick = {
                                viewModel.onMarkWatchedRequested()
                            }
                        ) {
                            Text(if (uiState.isUpdatingWatchHistory) "Working..." else "Mark Watched")
                        }

                        Button(
                            modifier = Modifier.testTag("watch_unmark_button"),
                            enabled = !uiState.isUpdatingWatchHistory,
                            onClick = {
                                viewModel.onUnmarkWatchedRequested()
                            }
                        ) {
                            Text(if (uiState.isUpdatingWatchHistory) "Working..." else "Unmark")
                        }
                    }

                    Text(
                        modifier = Modifier.testTag("watch_auth_text"),
                        text =
                            "trakt_auth=${uiState.watchAuthState.traktAuthenticated} | " +
                                "simkl_auth=${uiState.watchAuthState.simklAuthenticated}",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Text(
                        modifier = Modifier.testTag("watch_status_text"),
                        text = uiState.watchStatusMessage,
                        style = MaterialTheme.typography.bodySmall
                    )

                    val historyText =
                        if (uiState.watchEntries.isEmpty()) {
                            "history=none"
                        } else {
                            "history=" + uiState.watchEntries.take(6).joinToString(separator = " | ") { entry ->
                                val suffix =
                                    if (entry.season != null && entry.episode != null) {
                                        ":${entry.season}:${entry.episode}"
                                    } else {
                                        ""
                                    }
                                "${entry.contentId}$suffix"
                            }
                        }
                    Text(
                        modifier = Modifier.testTag("watch_history_text"),
                        text = historyText,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Supabase Sync", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Cloud push/pull for addons and watched history with Trakt-aware gating.",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    OutlinedTextField(
                        value = uiState.supabaseEmail,
                        onValueChange = viewModel::onSupabaseEmailChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("supabase_email_input"),
                        label = { Text("Supabase email") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                    )

                    OutlinedTextField(
                        value = uiState.supabasePassword,
                        onValueChange = viewModel::onSupabasePasswordChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("supabase_password_input"),
                        label = { Text("Supabase password") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )

                    OutlinedTextField(
                        value = uiState.supabasePin,
                        onValueChange = viewModel::onSupabasePinChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("supabase_pin_input"),
                        label = { Text("Sync PIN") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    OutlinedTextField(
                        value = uiState.supabaseSyncCode,
                        onValueChange = viewModel::onSupabaseSyncCodeChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("supabase_code_input"),
                        label = { Text("Sync code") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier.testTag("supabase_initialize_button"),
                            enabled = !uiState.isUpdatingSupabase,
                            onClick = { viewModel.onInitializeSupabaseRequested() }
                        ) {
                            Text("Initialize")
                        }

                        Button(
                            modifier = Modifier.testTag("supabase_signup_button"),
                            enabled = !uiState.isUpdatingSupabase,
                            onClick = { viewModel.onSupabaseSignUpRequested() }
                        ) {
                            Text("Sign Up")
                        }

                        Button(
                            modifier = Modifier.testTag("supabase_signin_button"),
                            enabled = !uiState.isUpdatingSupabase,
                            onClick = { viewModel.onSupabaseSignInRequested() }
                        ) {
                            Text("Sign In")
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier.testTag("supabase_signout_button"),
                            enabled = !uiState.isUpdatingSupabase,
                            onClick = { viewModel.onSupabaseSignOutRequested() }
                        ) {
                            Text("Sign Out")
                        }

                        Button(
                            modifier = Modifier.testTag("supabase_push_button"),
                            enabled = !uiState.isUpdatingSupabase,
                            onClick = { viewModel.onSupabasePushRequested() }
                        ) {
                            Text("Push")
                        }

                        Button(
                            modifier = Modifier.testTag("supabase_pull_button"),
                            enabled = !uiState.isUpdatingSupabase,
                            onClick = { viewModel.onSupabasePullRequested() }
                        ) {
                            Text("Pull")
                        }

                        Button(
                            modifier = Modifier.testTag("supabase_sync_now_button"),
                            enabled = !uiState.isUpdatingSupabase,
                            onClick = { viewModel.onSupabaseSyncNowRequested() }
                        ) {
                            Text(if (uiState.isUpdatingSupabase) "Working..." else "Sync Now")
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier.testTag("supabase_generate_code_button"),
                            enabled = !uiState.isUpdatingSupabase,
                            onClick = { viewModel.onSupabaseGenerateCodeRequested() }
                        ) {
                            Text("Generate Code")
                        }

                        Button(
                            modifier = Modifier.testTag("supabase_claim_code_button"),
                            enabled = !uiState.isUpdatingSupabase,
                            onClick = { viewModel.onSupabaseClaimCodeRequested() }
                        ) {
                            Text("Claim Code")
                        }
                    }

                    Text(
                        modifier = Modifier.testTag("supabase_auth_text"),
                        text =
                            "configured=${uiState.supabaseAuthState.configured} | " +
                                "auth=${uiState.supabaseAuthState.authenticated} | " +
                                "anon=${uiState.supabaseAuthState.anonymous} | " +
                                "user=${uiState.supabaseAuthState.userId ?: "none"}",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Text(
                        modifier = Modifier.testTag("supabase_status_text"),
                        text = uiState.supabaseStatusMessage,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun ExoPlayerSurface(
    modifier: Modifier = Modifier,
    bind: (PlayerView) -> Unit
) {
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                useController = true
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                bind(this)
            }
        },
        update = { playerView ->
            bind(playerView)
        }
    )
}

@Composable
private fun VlcPlayerSurface(
    modifier: Modifier = Modifier,
    create: (android.content.Context) -> android.view.SurfaceView,
    attach: (android.view.SurfaceView) -> Unit
) {
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            create(viewContext)
        },
        update = { surfaceView ->
            attach(surfaceView)
        }
    )
}

private fun PlaybackEngine.toNativeEngine(): NativePlaybackEngine {
    return when (this) {
        PlaybackEngine.EXO -> NativePlaybackEngine.EXO
        PlaybackEngine.VLC -> NativePlaybackEngine.VLC
    }
}

private val imdbIdRegex = Regex("tt\\d+")

private fun String.toImdbIdOrNull(): String? {
    val normalized = trim()
    return normalized.takeIf { it.matches(imdbIdRegex) }
}

private fun String.toPositiveIntOrNull(): Int? {
    return trim().toIntOrNull()?.takeIf { it > 0 }
}

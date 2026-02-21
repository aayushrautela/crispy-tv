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
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import com.crispy.rewrite.introskip.IntroSkipButtonOverlay
import com.crispy.rewrite.introskip.IntroSkipInterval
import com.crispy.rewrite.introskip.IntroSkipRequest
import com.crispy.rewrite.home.HomeHeroItem
import com.crispy.rewrite.home.HomeViewModel
import com.crispy.rewrite.home.HomeCatalogSectionUi
import com.crispy.rewrite.details.DetailsRoute
import com.crispy.rewrite.settings.AddonsSettingsRoute
import com.crispy.rewrite.settings.HomeScreenSettingsRoute
import com.crispy.rewrite.settings.HomeScreenSettingsStore
import com.crispy.rewrite.settings.PlaybackSettingsRepositoryProvider
import com.crispy.rewrite.settings.PlaybackSettingsScreen
import com.crispy.rewrite.settings.ProviderAuthPortalRoute
import com.crispy.rewrite.settings.SettingsScreen
import com.crispy.rewrite.search.SearchResultsContent
import com.crispy.rewrite.search.SearchViewModel
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
import androidx.compose.material3.AppBarWithSearch
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarValue
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSearchBarState
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
import com.crispy.rewrite.home.ContinueWatchingItem
import com.crispy.rewrite.nativeengine.playback.NativePlaybackEngine
import com.crispy.rewrite.nativeengine.playback.NativePlaybackEvent
import com.crispy.rewrite.ui.theme.Dimensions
import com.crispy.rewrite.ui.theme.responsivePageHorizontalPadding
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
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
                HomePage(
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HomePage(
    onHeroClick: (HomeHeroItem) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onCatalogItemClick: (CatalogItem) -> Unit,
    onCatalogSeeAllClick: (CatalogSectionRef) -> Unit,
    onProfileClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val viewModel: HomeViewModel = viewModel(
        factory = remember(appContext) {
            HomeViewModel.factory(appContext)
        }
    )
    val heroState by viewModel.heroState.collectAsStateWithLifecycle()
    val continueWatchingState by viewModel.continueWatchingState.collectAsStateWithLifecycle()
    val upNextState by viewModel.upNextState.collectAsStateWithLifecycle()
    val catalogSectionsState by viewModel.catalogSectionsState.collectAsStateWithLifecycle()

    val searchViewModel: SearchViewModel = viewModel(
        factory = remember(appContext) {
            SearchViewModel.factory(appContext)
        }
    )
    val searchUiState by searchViewModel.uiState.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val searchBarState = rememberSearchBarState()
    val textFieldState = rememberTextFieldState()
    val queryText = textFieldState.text.toString()

    LaunchedEffect(queryText) {
        if (queryText != searchUiState.query) {
            searchViewModel.updateQuery(queryText)
        }
    }

    LaunchedEffect(searchBarState.currentValue) {
        if (searchBarState.currentValue == SearchBarValue.Collapsed) {
            if (searchUiState.query.isNotBlank()) {
                searchViewModel.clearQuery()
            }
            if (queryText.isNotBlank()) {
                textFieldState.setTextAndPlaceCursorAtEnd("")
            }
        }
    }

    BackHandler(enabled = searchBarState.currentValue == SearchBarValue.Expanded) {
        scope.launch {
            searchBarState.animateToCollapsed()
        }
    }

    val scrollBehavior = SearchBarDefaults.enterAlwaysSearchBarScrollBehavior()

    val inputField: @Composable () -> Unit = {
        SearchBarDefaults.InputField(
            searchBarState = searchBarState,
            textFieldState = textFieldState,
            onSearch = {
                scope.launch {
                    searchBarState.animateToCollapsed()
                }
            },
            placeholder = {
                Text("Find your next watch")
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null
                )
            },
            trailingIcon = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(end = 16.dp)
                ) {
                    if (queryText.isNotBlank()) {
                        IconButton(
                            onClick = {
                                searchViewModel.clearQuery()
                                textFieldState.setTextAndPlaceCursorAtEnd("")
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Clear,
                                contentDescription = "Clear"
                            )
                        }
                    } else {
                        IconButton(onClick = { /* AI Search */ }) {
                            Icon(
                                imageVector = Icons.Outlined.AutoAwesome,
                                contentDescription = "AI Search"
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { onProfileClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Person,
                            contentDescription = "Profile",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        )
    }

    val horizontalPadding = responsivePageHorizontalPadding()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AppBarWithSearch(
                modifier = Modifier.padding(horizontal = horizontalPadding),
                state = searchBarState,
                inputField = inputField,
                colors = SearchBarDefaults.appBarWithSearchColors(
                    appBarContainerColor = Color.Transparent,
                    scrolledAppBarContainerColor = Color.Transparent
                ),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                scrollBehavior = scrollBehavior
            )

            ExpandedFullScreenSearchBar(
                state = searchBarState,
                inputField = inputField
            ) {
                SearchResultsContent(
                    uiState = searchUiState,
                    onMediaTypeChange = searchViewModel::setMediaType,
                    onCatalogToggle = searchViewModel::toggleCatalog,
                    onItemClick = { item ->
                        onCatalogItemClick(item)
                        scope.launch {
                            searchBarState.animateToCollapsed()
                        }
                        searchViewModel.clearQuery()
                        textFieldState.setTextAndPlaceCursorAtEnd("")
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding()
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                top = innerPadding.calculateTopPadding(),
                bottom = Dimensions.PageBottomPadding
            ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SectionSpacing)
        ) {
            item(contentType = "hero") {
                when {
                    heroState.isLoading && heroState.items.isEmpty() -> {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Loading featured content...")
                            }
                        }
                    }

                    heroState.items.isEmpty() -> {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = heroState.statusMessage,
                                modifier = Modifier.padding(Dimensions.CardInternalPadding)
                            )
                        }
                    }

                     else -> {
                        HomeHeroCarousel(
                            items = heroState.items,
                            selectedId = heroState.selectedId,
                            onItemClick = onHeroClick
                        )
                     }
                 }
             }

            item(contentType = "continueWatching") {
                ContinueWatchingSection(
                    items = continueWatchingState.items,
                    statusMessage = continueWatchingState.statusMessage,
                    onItemClick = onContinueWatchingClick,
                    onHideItem = viewModel::hideContinueWatchingItem,
                    onRemoveItem = viewModel::removeContinueWatchingItem
                )
            }

            item(contentType = "upNext") {
                UpNextSection(
                    items = upNextState.items,
                    statusMessage = upNextState.statusMessage,
                    onItemClick = onContinueWatchingClick,
                    onHideItem = viewModel::hideContinueWatchingItem,
                    onRemoveItem = viewModel::removeContinueWatchingItem
                )
            }

            if (catalogSectionsState.sections.isNotEmpty()) {
                items(
                    items = catalogSectionsState.sections,
                    key = { it.section.key },
                    contentType = { "catalogSection" }
                ) { sectionUi ->
                    HomeCatalogSectionRow(
                        sectionUi = sectionUi,
                        onSeeAllClick = { onCatalogSeeAllClick(sectionUi.section) },
                        onItemClick = onCatalogItemClick
                    )
                }
            }
        }
    }
}

@Composable
private fun ContinueWatchingSection(
    items: List<ContinueWatchingItem>,
    statusMessage: String,
    onItemClick: (ContinueWatchingItem) -> Unit,
    onHideItem: (ContinueWatchingItem) -> Unit,
    onRemoveItem: (ContinueWatchingItem) -> Unit
) {
    if (items.isEmpty()) {
        return
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Continue Watching",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (statusMessage.isNotBlank()) {
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (items.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    ContinueWatchingCard(
                        item = item,
                        onClick = { onItemClick(item) },
                        onHideClick = { onHideItem(item) },
                        onRemoveClick = { onRemoveItem(item) },
                        onDetailsClick = { onItemClick(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun UpNextSection(
    items: List<ContinueWatchingItem>,
    statusMessage: String,
    onItemClick: (ContinueWatchingItem) -> Unit,
    onHideItem: (ContinueWatchingItem) -> Unit,
    onRemoveItem: (ContinueWatchingItem) -> Unit
) {
    if (items.isEmpty()) {
        return
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Up Next",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (statusMessage.isNotBlank()) {
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (items.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    UpNextCard(
                        item = item,
                        onClick = { onItemClick(item) },
                        onHideClick = { onHideItem(item) },
                        onRemoveClick = { onRemoveItem(item) },
                        onDetailsClick = { onItemClick(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun UpNextCard(
    item: ContinueWatchingItem,
    onClick: () -> Unit,
    onHideClick: () -> Unit,
    onRemoveClick: () -> Unit,
    onDetailsClick: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .width(260.dp)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(28.dp))
            .clickable(onClick = onClick)
    ) {
        if (!item.backdropUrl.isNullOrBlank()) {
            AsyncImage(
                model = item.backdropUrl,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.64f)
                        )
                    )
                )
        )

        // "UP NEXT" badge at top-left (like Nuvio)
        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 12.dp, top = 10.dp),
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.primary
        ) {
            Text(
                text = "UP NEXT",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }

        if (!item.logoUrl.isNullOrBlank()) {
            AsyncImage(
                model = item.logoUrl,
                contentDescription = "${item.title} logo",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(0.6f)
                    .height(56.dp)
                    .padding(top = 12.dp),
                contentScale = ContentScale.Fit
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
        ) {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), shape = MaterialTheme.shapes.small)
            ) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "Up next actions",
                    tint = Color.White
                )
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Details") },
                    onClick = {
                        menuExpanded = false
                        onDetailsClick()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Remove") },
                    onClick = {
                        menuExpanded = false
                        onRemoveClick()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Hide") },
                    onClick = {
                        menuExpanded = false
                        onHideClick()
                    }
                )
            }
        }

        // Subtitle at bottom-left showing episode info
        Text(
            text = upNextSubtitle(item),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.95f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Progress bar at the very bottom (like Nuvio)
        if (item.progressPercent > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.White.copy(alpha = 0.3f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(
                            fraction = (item.progressPercent / 100.0).coerceIn(0.0, 1.0).toFloat()
                        )
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

private fun upNextSubtitle(item: ContinueWatchingItem): String {
    val seasonEpisode =
        if (
            item.type.equals("series", ignoreCase = true) &&
                item.season != null &&
                item.episode != null
        ) {
            String.format(Locale.US, "S%02d:E%02d", item.season, item.episode)
        } else {
            null
        }
    val relativeWatched = DateUtils.getRelativeTimeSpanString(
        item.watchedAtEpochMs,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS
    ).toString()

    return listOfNotNull(seasonEpisode, relativeWatched).joinToString(separator = " • ")
}

@Composable
private fun HomeCatalogSectionRow(
    sectionUi: HomeCatalogSectionUi,
    onSeeAllClick: () -> Unit,
    onItemClick: (CatalogItem) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = sectionUi.section.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            FilledIconButton(
                onClick = onSeeAllClick,
                modifier = Modifier
                    .width(32.dp)
                    .height(48.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "See all",
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        if (sectionUi.statusMessage.isNotBlank() && sectionUi.items.isEmpty()) {
            Text(
                text = sectionUi.statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (sectionUi.isLoading && sectionUi.items.isEmpty()) {
                items(10) {
                    Card(
                        modifier = Modifier
                            .width(124.dp)
                            .aspectRatio(2f / 3f)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                    }
                }
            } else {
                items(sectionUi.items, key = { it.id }) { item ->
                    HomeCatalogPosterCard(
                        item = item,
                        onClick = { onItemClick(item) }
                    )
                }
            }
        }

    }
}

@Composable
private fun HomeCatalogPosterCard(
    item: CatalogItem,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier.width(124.dp)
    ) {
        Card(
            modifier = Modifier
                .aspectRatio(2f / 3f)
                .clip(MaterialTheme.shapes.large)
                .clickable(onClick = onClick)
        ) {
            val imageUrl = item.posterUrl ?: item.backdropUrl
            Box(modifier = Modifier.fillMaxSize()) {
                if (!imageUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                }
                
                if (!item.rating.isNullOrBlank()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = Color.Black.copy(alpha = 0.7f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Star,
                                contentDescription = null,
                                modifier = Modifier.height(12.dp),
                                tint = Color(0xFFFFC107)
                            )
                            Text(
                                text = item.rating,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
        
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .height(40.dp)
        )
    }
}

@Composable
private fun ContinueWatchingCard(
    item: ContinueWatchingItem,
    onClick: () -> Unit,
    onHideClick: () -> Unit,
    onRemoveClick: () -> Unit,
    onDetailsClick: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .width(260.dp)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(28.dp))
            .clickable(onClick = onClick)
    ) {
        if (!item.backdropUrl.isNullOrBlank()) {
            AsyncImage(
                model = item.backdropUrl,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.64f)
                        )
                    )
                )
        )

        if (!item.logoUrl.isNullOrBlank()) {
            AsyncImage(
                model = item.logoUrl,
                contentDescription = "${item.title} logo",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(0.6f)
                    .height(56.dp)
                    .padding(top = 12.dp),
                contentScale = ContentScale.Fit
            )
        } else {
            Text(
                text = item.title,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
        ) {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), shape = MaterialTheme.shapes.small)
            ) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "Continue watching actions",
                    tint = Color.White
                )
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Details") },
                    onClick = {
                        menuExpanded = false
                        onDetailsClick()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Remove") },
                    onClick = {
                        menuExpanded = false
                        onRemoveClick()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Hide") },
                    onClick = {
                        menuExpanded = false
                        onHideClick()
                    }
                )
            }
        }

        Text(
            text = continueWatchingSubtitle(item),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.95f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun continueWatchingSubtitle(item: ContinueWatchingItem): String {
    val upNext = if (item.isUpNextPlaceholder) "Up Next" else null
    val seasonEpisode =
        if (
            item.type.equals("series", ignoreCase = true) &&
                item.season != null &&
                item.episode != null
        ) {
            String.format(Locale.US, "S%02d:E%02d", item.season, item.episode)
        } else {
            null
        }
    val relativeWatched = DateUtils.getRelativeTimeSpanString(
        item.watchedAtEpochMs,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS
    ).toString()

    return listOfNotNull(upNext, seasonEpisode, relativeWatched).joinToString(separator = " • ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeHeroCarousel(
    items: List<HomeHeroItem>,
    selectedId: String?,
    onItemClick: (HomeHeroItem) -> Unit
) {
    if (items.isEmpty()) {
        return
    }

    val initialIndex = remember(selectedId, items) {
        selectedId?.let { id ->
            items.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: 0
        } ?: 0
    }
    val state = rememberCarouselState(initialItem = initialIndex) { items.size }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(340.dp),
        contentAlignment = Alignment.Center
    ) {
        HorizontalMultiBrowseCarousel(
            state = state,
            preferredItemWidth = 320.dp,
            itemSpacing = 16.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
        ) { index ->
            val item = items[index]

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .maskClip(RoundedCornerShape(28.dp))
                    .clickable { onItemClick(item) }
            ) {
                AsyncImage(
                    model = item.backdropUrl,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.72f)
                                )
                            )
                        )
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    val subtitle = listOfNotNull(
                        item.year,
                        item.genres.firstOrNull()
                    ).joinToString(" • ")

                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceholderPage(title: String) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "$title page")
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

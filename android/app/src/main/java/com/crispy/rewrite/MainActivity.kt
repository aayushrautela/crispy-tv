package com.crispy.rewrite

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.PlayerView
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.crispy.rewrite.nativeengine.playback.NativePlaybackEngine
import com.crispy.rewrite.nativeengine.playback.NativePlaybackEvent
import com.crispy.rewrite.player.MetadataLabMediaType
import com.crispy.rewrite.player.PlaybackEngine
import com.crispy.rewrite.player.PlaybackLabViewModel
import com.crispy.rewrite.ui.theme.CrispyRewriteTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CrispyRewriteTheme {
                AppShell()
            }
        }
    }
}

private enum class TopLevelDestination(
    val route: String,
    val label: String,
    val marker: String
) {
    Home(route = "home", label = "Home", marker = "H"),
    Search(route = "search", label = "Search", marker = "S"),
    Discover(route = "discover", label = "Discover", marker = "D"),
    Library(route = "library", label = "Library", marker = "L"),
    Settings(route = "settings", label = "Settings", marker = "Se")
}

@Composable
private fun AppShell() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                TopLevelDestination.entries.forEach { destination ->
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
                        icon = { Text(destination.marker) },
                        label = { Text(destination.label) }
                    )
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
        ) {
            composable(TopLevelDestination.Home.route) {
                AppRoot()
            }
            composable(TopLevelDestination.Search.route) { PlaceholderPage(title = "Search") }
            composable(TopLevelDestination.Discover.route) { PlaceholderPage(title = "Discover") }
            composable(TopLevelDestination.Library.route) { PlaceholderPage(title = "Library") }
            composable(TopLevelDestination.Settings.route) { PlaceholderPage(title = "Settings") }
        }
    }
}

private data class HomeShelfItem(
    val title: String,
    val subtitle: String
)

@Composable
private fun HomeHeaderRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Crispy",
            style = MaterialTheme.typography.headlineMedium
        )
        Card {
            Text(
                text = "Profile",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun HomeHeroCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Featured tonight", style = MaterialTheme.typography.titleLarge)
            Text(
                "Top picks and continue-watching shortcuts, inspired by crispy-native home.",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {}) {
                    Text("Play")
                }
                Button(onClick = {}) {
                    Text("Details")
                }
            }
        }
    }
}

@Composable
private fun HomeShelfRow(
    title: String,
    items: List<HomeShelfItem>
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 2.dp)
        ) {
            items(items) { item ->
                Card(modifier = Modifier.width(220.dp)) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall)
                        Text(item.subtitle, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceholderPage(title: String) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(title) })
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "$title page")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot() {
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

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HomeHeaderRow()
            HomeHeroCard()
            HomeShelfRow(
                title = "Continue Watching",
                items = listOf(
                    HomeShelfItem(title = "Game of Thrones", subtitle = "S01E02"),
                    HomeShelfItem(title = "The Last of Us", subtitle = "S01E05"),
                    HomeShelfItem(title = "Interstellar", subtitle = "1h 04m left")
                )
            )
            HomeShelfRow(
                title = "Trending",
                items = listOf(
                    HomeShelfItem(title = "Dune: Part Two", subtitle = "Movie"),
                    HomeShelfItem(title = "Severance", subtitle = "Series"),
                    HomeShelfItem(title = "Shogun", subtitle = "Series")
                )
            )
            HomeShelfRow(
                title = "Top picks for you",
                items = listOf(
                    HomeShelfItem(title = "Arrival", subtitle = "Sci-fi"),
                    HomeShelfItem(title = "The Batman", subtitle = "Action"),
                    HomeShelfItem(title = "Dark", subtitle = "Mystery")
                )
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

                    if (playerVisible) {
                        when (uiState.activeEngine) {
                            PlaybackEngine.EXO -> {
                                ExoPlayerSurface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(240.dp),
                                    bind = playbackController::bindExoPlayerView
                                )
                            }

                            PlaybackEngine.VLC -> {
                                VlcPlayerSurface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(240.dp),
                                    create = playbackController::createVlcSurfaceView,
                                    attach = playbackController::attachVlcSurface
                                )
                            }
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

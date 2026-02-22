package com.crispy.tv.ui.labs

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crispy.tv.PlaybackDependencies
import com.crispy.tv.introskip.IntroSkipInterval
import com.crispy.tv.introskip.IntroSkipRequest
import com.crispy.tv.nativeengine.playback.NativePlaybackEngine
import com.crispy.tv.nativeengine.playback.NativePlaybackEvent
import com.crispy.tv.player.PlaybackEngine
import com.crispy.tv.player.PlaybackLabViewModel
import com.crispy.tv.settings.PlaybackSettingsRepositoryProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LabsScreen() {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }

    val metadataResolver = remember(appContext) {
        PlaybackDependencies.metadataResolverFactory(appContext)
    }
    val catalogSearchService = remember(appContext) {
        PlaybackDependencies.catalogSearchServiceFactory(appContext)
    }
    val watchHistoryService = remember(appContext) {
        PlaybackDependencies.watchHistoryServiceFactory(appContext)
    }
    val supabaseSyncService = remember(appContext, watchHistoryService) {
        PlaybackDependencies.supabaseSyncServiceFactory(appContext, watchHistoryService)
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
        PlaybackDependencies.introSkipServiceFactory(appContext)
    }

    val playbackController = remember(context) {
        PlaybackDependencies.playbackControllerFactory(context) { event ->
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
        PlaybackDependencies.torrentResolverFactory(context)
    }

    var playerVisible by remember { mutableStateOf(false) }
    var introSkipImdbIdInput by rememberSaveable { mutableStateOf("") }
    var introSkipSeasonInput by rememberSaveable { mutableStateOf("") }
    var introSkipEpisodeInput by rememberSaveable { mutableStateOf("") }
    var introSkipMalIdInput by rememberSaveable { mutableStateOf("") }
    var introSkipKitsuIdInput by rememberSaveable { mutableStateOf("") }
    var introSkipIntervals by remember { mutableStateOf(emptyList<IntroSkipInterval>()) }
    var introSkipStatusMessage by remember {
        mutableStateOf("Provide episode identity to enable intro skip.")
    }
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
            viewModel.onPlaybackLaunchFailed(
                "Notification permission denied. Torrent service may fail on Android 13+."
            )
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

    LaunchedEffect(
        playbackSettings.skipIntroEnabled,
        introSkipRequest,
        uiState.playbackRequestVersion
    ) {
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
            introSkipStatusMessage =
                "Enter season and episode with at least one IMDb, MAL, or Kitsu ID."
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

            PlaybackLabSection(
                uiState = uiState,
                mergedImdbId = mergedImdbId,
                introSkipImdbIdInput = introSkipImdbIdInput,
                onIntroSkipImdbIdInputChanged = { introSkipImdbIdInput = it },
                introSkipSeasonInput = introSkipSeasonInput,
                onIntroSkipSeasonInputChanged = { introSkipSeasonInput = it },
                introSkipEpisodeInput = introSkipEpisodeInput,
                onIntroSkipEpisodeInputChanged = { introSkipEpisodeInput = it },
                introSkipMalIdInput = introSkipMalIdInput,
                onIntroSkipMalIdInputChanged = { introSkipMalIdInput = it },
                introSkipKitsuIdInput = introSkipKitsuIdInput,
                onIntroSkipKitsuIdInputChanged = { introSkipKitsuIdInput = it },
                introSkipLoading = introSkipLoading,
                introSkipStatusMessage = introSkipStatusMessage,
                onEngineSelected = viewModel::onEngineSelected,
                onPlaySampleRequested = viewModel::onPlaySampleRequested,
                onMagnetChanged = viewModel::onMagnetChanged,
                onStartTorrentRequested = startTorrent@{
                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        return@startTorrent
                    }

                    val magnet = viewModel.onStartTorrentRequested() ?: return@startTorrent

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
                            viewModel.onTorrentPlaybackFailed(
                                error.message ?: "unknown torrent error"
                            )
                        }
                    }
                },
                skipIntroEnabled = playbackSettings.skipIntroEnabled,
                introSkipIntervals = introSkipIntervals,
                playbackPositionMs = playbackPositionMs,
                playerVisible = playerVisible,
                bindExoPlayerView = playbackController::bindExoPlayerView,
                createVlcSurfaceView = playbackController::createVlcSurfaceView,
                attachVlcSurface = playbackController::attachVlcSurface,
                onSkipRequested = playbackController::seekTo
            )

            MetadataLabSection(uiState = uiState, viewModel = viewModel)
            CatalogLabSection(uiState = uiState, viewModel = viewModel)
            WatchHistorySyncSection(uiState = uiState, viewModel = viewModel)
            SupabaseSyncSection(uiState = uiState, viewModel = viewModel)
        }
    }
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

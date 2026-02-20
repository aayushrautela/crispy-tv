@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.crispy.rewrite.details

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.crispy.rewrite.home.MediaDetails
import com.crispy.rewrite.home.MediaVideo

private const val SampleTrailerUrl =
    "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/1080/Big_Buck_Bunny_1080_10s_30MB.mp4"

@Composable
fun DetailsRoute(
    itemId: String,
    onBack: () -> Unit
) {
    val appContext = LocalContext.current.applicationContext
    val viewModel: DetailsViewModel =
        viewModel(
            key = itemId,
            factory = remember(appContext, itemId) { DetailsViewModel.factory(appContext, itemId) }
        )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    DetailsScreen(
        uiState = uiState,
        onBack = onBack,
        onRetry = viewModel::reload,
        onSeasonSelected = viewModel::onSeasonSelected
    )
}

@Composable
private fun DetailsScreen(
    uiState: DetailsUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSeasonSelected: (Int) -> Unit
) {
    val details = uiState.details
    val listState = rememberLazyListState()
    val topBarAlpha by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) {
                1f
            } else {
                (listState.firstVisibleItemScrollOffset / 420f).coerceIn(0f, 1f)
            }
        }
    }

    val containerColor = MaterialTheme.colorScheme.surface.copy(alpha = topBarAlpha)
    val contentColor = lerp(Color.White, MaterialTheme.colorScheme.onSurface, topBarAlpha)

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .navigationBarsPadding(),
            state = listState
        ) {
            item {
                HeroSection(details = details)
            }

            item {
                DetailsBody(
                    uiState = uiState,
                    onRetry = onRetry,
                    onSeasonSelected = onSeasonSelected
                )
            }
        }

        TopAppBar(
            windowInsets = TopAppBarDefaults.windowInsets,
            title = {
                Text(
                    text = if (topBarAlpha > 0.65f) details?.title ?: "Details" else "",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = contentColor
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = contentColor
                    )
                }
            },
            colors =
                TopAppBarDefaults.topAppBarColors(
                    containerColor = containerColor,
                    titleContentColor = contentColor,
                    navigationIconContentColor = contentColor,
                    actionIconContentColor = contentColor
                )
        )
    }
}

@Composable
private fun HeroSection(details: MediaDetails?) {
    val shape = MaterialTheme.shapes.extraLarge
    val heroHeight = 16f / 9f

    var isTrailerPlaying by rememberSaveable(details?.id) { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(heroHeight)
            .padding(12.dp)
            .clip(shape)
    ) {
        if (details == null) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            return
        }

        if (isTrailerPlaying) {
            TrailerPlayer(
                modifier = Modifier.fillMaxSize(),
                onClose = { isTrailerPlaying = false }
            )
        } else {
            val imageUrl = details.backdropUrl ?: details.posterUrl
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = details.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {}
            }

            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors =
                                    listOf(
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.55f)
                                    )
                            )
                        )
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = details.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { /* TODO: hook up playback */ }) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Watch")
                    }

                    FilledTonalButton(onClick = { isTrailerPlaying = true }) {
                        Text("Trailer")
                    }
                }
            }
        }
    }
}

@Composable
private fun TrailerPlayer(
    modifier: Modifier = Modifier,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val exoPlayer =
        remember {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(SampleTrailerUrl))
                prepare()
                playWhenReady = true
            }
        }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = true
                    player = exoPlayer
                }
            },
            update = { view ->
                view.player = exoPlayer
            }
        )

        IconButton(
            onClick = {
                exoPlayer.stop()
                onClose()
            },
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close",
                tint = Color.White
            )
        }
    }
}

@Composable
private fun DetailsBody(
    uiState: DetailsUiState,
    onRetry: () -> Unit,
    onSeasonSelected: (Int) -> Unit
) {
    val details = uiState.details
    val widthDp = LocalConfiguration.current.screenWidthDp
    val horizontalPadding =
        when {
            widthDp >= 1024 -> 32.dp
            widthDp >= 768 -> 24.dp
            else -> 16.dp
        }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
            .padding(bottom = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(14.dp))

        if (details == null) {
            if (uiState.isLoading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(uiState.statusMessage.ifBlank { "Loading..." })
                }
            } else {
                Text(uiState.statusMessage.ifBlank { "Unable to load details." })
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onRetry) { Text("Retry") }
            }
            return
        }

        Text(
            text = details.title,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(10.dp))

        MetaChips(details = details)

        if (details.genres.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(details.genres) { genre ->
                    AssistChip(onClick = { }, label = { Text(genre) })
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        ExpandableDescription(text = details.description)

        if (details.directors.isNotEmpty() || details.creators.isNotEmpty()) {
            Spacer(modifier = Modifier.height(14.dp))
            if (details.directors.isNotEmpty()) {
                Text(
                    text = "Director: ${details.directors.joinToString()}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (details.creators.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Creator: ${details.creators.joinToString()}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        if (details.cast.isNotEmpty()) {
            Spacer(modifier = Modifier.height(18.dp))
            Text("Cast", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(details.cast.take(24)) { name ->
                    AssistChip(onClick = { }, label = { Text(name) })
                }
            }
        }

        if (details.mediaType == "series" && details.videos.isNotEmpty()) {
            Spacer(modifier = Modifier.height(22.dp))
            Text("Episodes", style = MaterialTheme.typography.titleMedium)

            val seasons = uiState.seasons
            val selectedSeason = uiState.selectedSeasonOrFirst
            if (seasons.isNotEmpty() && selectedSeason != null) {
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(seasons) { season ->
                        FilterChip(
                            selected = season == selectedSeason,
                            onClick = { onSeasonSelected(season) },
                            label = { Text("Season $season") }
                        )
                    }
                }

                val episodes =
                    details.videos
                        .filter { it.season == selectedSeason }
                        .sortedWith(compareBy<MediaVideo> { it.episode ?: Int.MAX_VALUE }.thenBy { it.title })
                        .take(50)

                Spacer(modifier = Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    episodes.forEach { video ->
                        EpisodeRow(video = video)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaChips(details: MediaDetails) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        details.year?.takeIf { it.isNotBlank() }?.let { year ->
            item { AssistChip(onClick = { }, label = { Text(year) }) }
        }
        formatRuntime(details.runtime)?.let { runtime ->
            item { AssistChip(onClick = { }, label = { Text(runtime) }) }
        }
        details.certification?.takeIf { it.isNotBlank() }?.let { rating ->
            item { AssistChip(onClick = { }, label = { Text(rating) }) }
        }
        details.rating?.takeIf { it.isNotBlank() }?.let { rating ->
            item {
                AssistChip(
                    onClick = { },
                    label = { Text(rating) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun ExpandableDescription(text: String?) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    val content = text?.trim().orEmpty()
    if (content.isBlank()) {
        Text(
            text = "No description available.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    Column(modifier = Modifier.animateContentSize()) {
        Text(
            text = content,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis
        )
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Show less" else "Show more")
        }
    }
}

@Composable
private fun EpisodeRow(video: MediaVideo) {
    ElevatedCard(onClick = { /* TODO: show streams/play */ }) {
        ListItem(
            headlineContent = {
                val prefix =
                    when {
                        video.season != null && video.episode != null -> "S${video.season}E${video.episode}"
                        video.episode != null -> "E${video.episode}"
                        else -> ""
                    }
                Text(
                    text = if (prefix.isBlank()) video.title else "$prefix • ${video.title}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            supportingContent = {
                val supporting = video.overview ?: video.released
                if (!supporting.isNullOrBlank()) {
                    Text(
                        text = supporting,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            trailingContent = {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null
                )
            }
        )
    }
}

private fun formatRuntime(runtime: String?): String? {
    val input = runtime?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val hourMatch = Regex("(\\d+)\\s*h").find(input)
    val minMatch = Regex("(\\d+)\\s*min").find(input)
    if (hourMatch != null || minMatch != null) {
        val hours = hourMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val minutes = minMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return buildString {
            if (hours > 0) append("${hours}H")
            if (hours > 0 && minutes > 0) append(" ")
            if (minutes > 0) append("${minutes}M")
            if (isEmpty()) append(input.uppercase())
        }
    }

    val numericMinutes = input.toIntOrNull()
    if (numericMinutes != null && numericMinutes > 0) {
        val hours = numericMinutes / 60
        val minutes = numericMinutes % 60
        return buildString {
            if (hours > 0) append("${hours}H")
            if (hours > 0 && minutes > 0) append(" ")
            if (minutes > 0) append("${minutes}M")
        }
    }

    return input.uppercase()
}

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.crispy.rewrite.details

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.crispy.rewrite.home.MediaDetails
import com.crispy.rewrite.home.MediaVideo
import com.crispy.rewrite.ui.theme.Dimensions
import com.crispy.rewrite.ui.theme.responsivePageHorizontalPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SampleTrailerUrl =
    "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/1080/Big_Buck_Bunny_1080_10s_30MB.mp4"

@Stable
private data class DetailsPaletteColors(
    val pageBackground: Color,
    val onPageBackground: Color,
    val accent: Color,
    val onAccent: Color,
    val pillBackground: Color,
    val onPillBackground: Color
)

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
        onSeasonSelected = viewModel::onSeasonSelected,
        onToggleWatchlist = viewModel::toggleWatchlist
    )
}

@Composable
private fun DetailsScreen(
    uiState: DetailsUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSeasonSelected: (Int) -> Unit,
    onToggleWatchlist: () -> Unit
) {
    val details = uiState.details
    val listState = rememberLazyListState()
    val palette = rememberDetailsPaletteColors(imageUrl = details?.backdropUrl ?: details?.posterUrl)

    val baseScheme = MaterialTheme.colorScheme
    val detailsScheme =
        remember(palette, baseScheme) {
            baseScheme.copy(
                primary = palette.accent,
                onPrimary = palette.onAccent,
                background = palette.pageBackground,
                onBackground = palette.onPageBackground,
                surface = lerp(palette.pageBackground, palette.onPageBackground, 0.08f),
                onSurface = palette.onPageBackground,
                surfaceVariant = lerp(palette.pageBackground, palette.onPageBackground, 0.12f),
                onSurfaceVariant = palette.onPageBackground.copy(alpha = 0.78f)
            )
        }
    val topBarAlpha by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) {
                1f
            } else {
                (listState.firstVisibleItemScrollOffset / 420f).coerceIn(0f, 1f)
            }
        }
    }

    val containerColor = palette.pageBackground.copy(alpha = topBarAlpha)
    val contentColor = lerp(Color.White, palette.onPageBackground, topBarAlpha)

    MaterialTheme(colorScheme = detailsScheme) {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .navigationBarsPadding(),
                state = listState
            ) {
                item {
                    HeroSection(details = details, palette = palette)
                }

            item {
                HeaderInfoSection(
                    details = details,
                    isWatched = uiState.isWatched,
                    isInWatchlist = uiState.isInWatchlist,
                    isMutating = uiState.isMutating,
                    palette = palette,
                    onToggleWatchlist = onToggleWatchlist
                )
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
}

@Composable
private fun HeroSection(
    details: MediaDetails?,
    palette: DetailsPaletteColors
) {
    val configuration = LocalConfiguration.current
    val horizontalPadding = responsivePageHorizontalPadding()
    val heroHeight = (configuration.screenHeightDp.dp * 0.52f).coerceIn(340.dp, 520.dp)
    var isTrailerPlaying by rememberSaveable(details?.id) { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(heroHeight)
    ) {
        val heightPx = with(LocalDensity.current) { maxHeight.toPx() }

        if (details == null) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = palette.pillBackground
            ) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = palette.onPillBackground)
                }
            }
            return@BoxWithConstraints
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
                    color = palette.pillBackground
                ) {}
            }

            // Top scrim for app bar/buttons readability.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.55f),
                            0.38f to Color.Transparent
                        )
                    )
            )

            // Bottom fade to merge hero into the page background.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops =
                                arrayOf(
                                    0f to Color.Transparent,
                                    0.58f to Color.Transparent,
                                    1f to palette.pageBackground
                                ),
                            startY = 0f,
                            endY = heightPx
                        )
                    )
            )

            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(MaterialTheme.shapes.extraLarge)
                    .clickable { isTrailerPlaying = true },
                color = Color.Black.copy(alpha = 0.34f),
                contentColor = Color.White
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text("Trailer", style = MaterialTheme.typography.labelLarge)
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = horizontalPadding)
                    .padding(bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val logoUrl = details.logoUrl?.trim().orEmpty()
                if (logoUrl.isNotBlank()) {
                    AsyncImage(
                        model = logoUrl,
                        contentDescription = details.title,
                        modifier = Modifier
                            .fillMaxWidth(0.84f)
                            .height(110.dp),
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart
                    )
                } else {
                    Text(
                        text = details.title,
                        style = MaterialTheme.typography.headlineLarge,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderInfoSection(
    details: MediaDetails?,
    isWatched: Boolean,
    isInWatchlist: Boolean,
    isMutating: Boolean,
    palette: DetailsPaletteColors,
    onToggleWatchlist: () -> Unit,
) {
    if (details == null) return

    val horizontalPadding = responsivePageHorizontalPadding()
    val genre = details.genres.firstOrNull()?.trim().orEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
            .padding(top = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (genre.isNotBlank()) {
            Text(
                text = genre,
                style = MaterialTheme.typography.labelLarge,
                color = palette.onPageBackground.copy(alpha = 0.86f)
            )
        }

        HeaderMetaRow(details = details, isWatched = isWatched, palette = palette)

        ExpandableDescription(
            text = details.description,
            textAlign = TextAlign.Center,
            textColor = palette.onPageBackground.copy(alpha = 0.9f),
            placeholderColor = palette.onPageBackground.copy(alpha = 0.7f)
        )

        Button(
            onClick = { /* TODO: hook up playback */ },
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            shape = MaterialTheme.shapes.extraLarge,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = palette.accent,
                    contentColor = palette.onAccent
                )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(10.dp))
                Text("Watch now", style = MaterialTheme.typography.titleMedium)
            }
        }

        DetailsQuickActionsRow(
            palette = palette,
            enabled = !isMutating,
            isInWatchlist = isInWatchlist,
            onToggleWatchlist = onToggleWatchlist
        )

        Spacer(modifier = Modifier.height(2.dp))
    }
}

@Composable
private fun DetailsQuickActionsRow(
    palette: DetailsPaletteColors,
    enabled: Boolean,
    isInWatchlist: Boolean,
    onToggleWatchlist: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        DetailsQuickAction(
            label = "Watchlist",
            selected = isInWatchlist,
            enabled = enabled,
            palette = palette,
            icon = if (isInWatchlist) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
            onClick = onToggleWatchlist
        )
    }
}

@Composable
private fun DetailsQuickAction(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    palette: DetailsPaletteColors,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    val container = if (selected) lerp(palette.pillBackground, palette.accent, 0.28f) else palette.pillBackground
    val iconTint = if (selected) palette.accent else palette.onPillBackground.copy(alpha = 0.92f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = 6.dp)
    ) {
        Surface(
            modifier = Modifier
                .size(52.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .clickable(enabled = enabled) { onClick() },
            color = container,
            contentColor = palette.onPillBackground
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = palette.onPageBackground.copy(alpha = if (enabled) 0.9f else 0.55f)
        )
    }
}


@Composable
private fun HeaderMetaRow(
    details: MediaDetails,
    isWatched: Boolean,
    palette: DetailsPaletteColors
) {
    val rating = details.rating?.trim().takeIf { !it.isNullOrBlank() }
    val certification = details.certification?.trim().takeIf { !it.isNullOrBlank() }
    val year = details.year?.trim().takeIf { !it.isNullOrBlank() }
    val runtime = formatRuntimeForHeader(details.runtime)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (rating != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = Color(0xFFFFD54F)
                )
                Text(
                    text = rating,
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.onPageBackground
                )
            }
        }

        if (certification != null) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = palette.pillBackground,
                contentColor = palette.onPillBackground
            ) {
                Text(
                    text = certification,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        if (year != null) {
            Text(
                text = year,
                style = MaterialTheme.typography.labelLarge,
                color = palette.onPageBackground.copy(alpha = 0.86f)
            )
        }

        if (runtime != null) {
            Text(
                text = runtime,
                style = MaterialTheme.typography.labelLarge,
                color = palette.onPageBackground.copy(alpha = 0.86f)
            )
        }

        if (isWatched) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = palette.pillBackground,
                contentColor = palette.onPillBackground
            ) {
                Text(
                    text = "Watched",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium
                )
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
    val horizontalPadding = responsivePageHorizontalPadding()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
            .padding(bottom = Dimensions.PageBottomPadding)
    ) {
        Spacer(modifier = Modifier.height(18.dp))

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
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = episodes,
                        key = { it.id }
                    ) { video ->
                        EpisodeCard(
                            video = video,
                            modifier = Modifier.width(280.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandableDescription(
    text: String?,
    textAlign: TextAlign = TextAlign.Start,
    textColor: Color = MaterialTheme.colorScheme.onBackground,
    placeholderColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    val content = text?.trim().orEmpty()
    if (content.isBlank()) {
        Text(
            text = "No description available.",
            style = MaterialTheme.typography.bodyMedium,
            color = placeholderColor,
            textAlign = textAlign
        )
        return
    }

    Column(modifier = Modifier.animateContentSize()) {
        Text(
            text = content,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            textAlign = textAlign,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis
        )
        TextButton(
            onClick = { expanded = !expanded },
            colors = ButtonDefaults.textButtonColors(contentColor = textColor.copy(alpha = 0.92f))
        ) {
            Text(if (expanded) "Show less" else "Show more")
        }
    }
}

@Composable
private fun EpisodeCard(
    video: MediaVideo,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier,
        onClick = { /* TODO: show streams/play */ }
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            ) {
                val thumbnail = video.thumbnailUrl?.trim().orEmpty()
                if (thumbnail.isNotBlank()) {
                    AsyncImage(
                        model = thumbnail,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        MaterialTheme.colorScheme.secondaryContainer
                                    )
                                )
                            )
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.55f)
                            )
                        )
                )

                val prefix = episodePrefix(video)
                if (prefix != null) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
                    ) {
                        Text(
                            text = prefix,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(44.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = Color.Black.copy(alpha = 0.35f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = Color.White
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                video.released?.takeIf { it.isNotBlank() }?.let { released ->
                    Text(
                        text = released,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                video.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                    Text(
                        text = overview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun episodePrefix(video: MediaVideo): String? {
    val season = video.season
    val episode = video.episode
    return when {
        season != null && episode != null -> "S${season}E${episode}"
        episode != null -> "E${episode}"
        else -> null
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

private fun formatRuntimeForHeader(runtime: String?): String? {
    val input = runtime?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val hourMatch = Regex("(\\d+)\\s*h").find(input)
    val minMatch = Regex("(\\d+)\\s*min").find(input)

    fun human(hours: Int, minutes: Int): String? {
        if (hours <= 0 && minutes <= 0) return null
        return buildString {
            if (hours > 0) append("${hours} hr")
            if (hours > 0 && minutes > 0) append(" ")
            if (minutes > 0) append("${minutes} min")
        }
    }

    if (hourMatch != null || minMatch != null) {
        val hours = hourMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val minutes = minMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return human(hours, minutes) ?: input
    }

    val numericMinutes = input.toIntOrNull()
    if (numericMinutes != null && numericMinutes > 0) {
        val hours = numericMinutes / 60
        val minutes = numericMinutes % 60
        return human(hours, minutes)
    }

    return input
}

@Composable
private fun rememberDetailsPaletteColors(imageUrl: String?): DetailsPaletteColors {
    val scheme = MaterialTheme.colorScheme
    val fallbackPage = scheme.background
    val fallbackAccent = scheme.primary

    var colors by remember(fallbackPage, fallbackAccent) {
        mutableStateOf(
            DetailsPaletteColors(
                pageBackground = fallbackPage,
                onPageBackground = scheme.onBackground,
                accent = fallbackAccent,
                onAccent = scheme.onPrimary,
                pillBackground = scheme.surface.copy(alpha = 0.72f),
                onPillBackground = scheme.onSurface
            )
        )
    }

    val context = LocalContext.current
    val imageLoader = context.imageLoader

    LaunchedEffect(imageUrl, fallbackPage, fallbackAccent) {
        if (imageUrl.isNullOrBlank()) return@LaunchedEffect

        val request =
            ImageRequest.Builder(context)
                .data(imageUrl)
                .allowHardware(false)
                .build()

        val result = imageLoader.execute(request)
        val drawable = (result as? SuccessResult)?.drawable ?: return@LaunchedEffect
        val bitmap = drawable.toBitmap()

        val palette =
            withContext(Dispatchers.Default) {
                Palette.from(bitmap)
                    .clearFilters()
                    .generate()
            }

        val bgArgb =
            palette.darkMutedSwatch?.rgb
                ?: palette.darkVibrantSwatch?.rgb
                ?: palette.mutedSwatch?.rgb
                ?: palette.vibrantSwatch?.rgb

        val accentArgb =
            palette.vibrantSwatch?.rgb
                ?: palette.lightVibrantSwatch?.rgb
                ?: palette.mutedSwatch?.rgb
                ?: palette.lightMutedSwatch?.rgb

        val extractedBg = bgArgb?.let { Color(it) }
        val extractedAccent = accentArgb?.let { Color(it) }

        val pageBackground = if (extractedBg != null) lerp(fallbackPage, extractedBg, 0.88f) else fallbackPage
        val accent = if (extractedAccent != null) lerp(fallbackAccent, extractedAccent, 0.9f) else fallbackAccent
        val onPage = contrastColor(pageBackground)
        val onAccent = contrastColor(accent)
        val pillBackground = lerp(pageBackground, onPage, 0.14f).copy(alpha = 0.72f)

        colors =
            DetailsPaletteColors(
                pageBackground = pageBackground,
                onPageBackground = onPage,
                accent = accent,
                onAccent = onAccent,
                pillBackground = pillBackground,
                onPillBackground = onPage
            )
    }

    return colors
}

private fun contrastColor(background: Color): Color {
    // 0.52 chosen to keep mid-tones leaning white.
    return if (background.luminance() > 0.52f) Color.Black else Color.White
}

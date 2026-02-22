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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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
import com.crispy.rewrite.home.HomeCatalogPosterCard
import com.crispy.rewrite.home.MediaDetails
import com.crispy.rewrite.home.MediaVideo
import com.crispy.rewrite.metadata.tmdb.TmdbCastMember
import com.crispy.rewrite.metadata.tmdb.TmdbEnrichment
import com.crispy.rewrite.metadata.tmdb.TmdbMovieDetails
import com.crispy.rewrite.metadata.tmdb.TmdbProductionEntity
import com.crispy.rewrite.metadata.tmdb.TmdbTitleDetails
import com.crispy.rewrite.metadata.tmdb.TmdbTvDetails
import com.crispy.rewrite.metadata.tmdb.TmdbTrailer
import com.crispy.rewrite.ui.theme.Dimensions
import com.crispy.rewrite.ui.theme.responsivePageHorizontalPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

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
    onBack: () -> Unit,
    onItemClick: (String) -> Unit = {}
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
        onItemClick = onItemClick,
        onRetry = viewModel::reload,
        onSeasonSelected = viewModel::onSeasonSelected,
        onToggleWatchlist = viewModel::toggleWatchlist
    )
}

@Composable
private fun DetailsScreen(
    uiState: DetailsUiState,
    onBack: () -> Unit,
    onItemClick: (String) -> Unit,
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
                        onSeasonSelected = onSeasonSelected,
                        onItemClick = onItemClick
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
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 160.dp)
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
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = horizontalPadding)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
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
                        alignment = Alignment.Center
                    )
                } else {
                    Text(
                        text = details.title,
                        style = MaterialTheme.typography.headlineLarge,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
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
    onSeasonSelected: (Int) -> Unit,
    onItemClick: (String) -> Unit
) {
    val details = uiState.details
    val tmdb = uiState.tmdbEnrichment
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

        if (uiState.tmdbIsLoading) {
            Spacer(modifier = Modifier.height(18.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Loading extras...", style = MaterialTheme.typography.bodyMedium)
            }
        }

        val tmdbCast = tmdb?.cast.orEmpty()
        if (tmdbCast.isNotEmpty()) {
            Spacer(modifier = Modifier.height(18.dp))
            Text("Cast", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(items = tmdbCast, key = { it.id }) { member ->
                    TmdbCastCard(member = member)
                }
            }
        } else if (details.cast.isNotEmpty()) {
            Spacer(modifier = Modifier.height(18.dp))
            Text("Cast", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(details.cast.take(24)) { name ->
                    AssistChip(onClick = { }, label = { Text(name) })
                }
            }
        }

        val trailers = tmdb?.trailers.orEmpty()
        if (trailers.isNotEmpty()) {
            Spacer(modifier = Modifier.height(18.dp))
            Text("Trailers", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(items = trailers, key = { it.id }) { trailer ->
                    TmdbTrailerCard(trailer = trailer, modifier = Modifier.width(280.dp))
                }
            }
        }

        val production = tmdb?.production.orEmpty()
        if (production.isNotEmpty()) {
            Spacer(modifier = Modifier.height(18.dp))
            Text("Production", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(items = production, key = { it.id }) { entity ->
                    TmdbProductionCard(entity = entity)
                }
            }
        }

        val titleDetails = tmdb?.titleDetails
        val facts = if (tmdb != null && titleDetails != null) tmdbFacts(tmdb, titleDetails) else emptyList()
        if (facts.isNotEmpty() || titleDetails?.tagline?.isNotBlank() == true) {
            Spacer(modifier = Modifier.height(18.dp))
            Text("Details", style = MaterialTheme.typography.titleMedium)

            titleDetails?.tagline?.takeIf { it.isNotBlank() }?.let { tagline ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = tagline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (facts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(facts) { fact ->
                        AssistChip(onClick = { }, label = { Text(fact) })
                    }
                }
            }
        }

        tmdb?.collection?.takeIf { it.parts.isNotEmpty() }?.let { collection ->
            Spacer(modifier = Modifier.height(18.dp))
            Text("Collection", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = collection.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(items = collection.parts, key = { it.id }) { item ->
                    HomeCatalogPosterCard(item = item, onClick = { onItemClick(item.id) })
                }
            }
        }

        val similar = tmdb?.similar.orEmpty()
        if (similar.isNotEmpty()) {
            Spacer(modifier = Modifier.height(18.dp))
            Text("Similar", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(items = similar, key = { it.id }) { item ->
                    HomeCatalogPosterCard(item = item, onClick = { onItemClick(item.id) })
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
private fun TmdbCastCard(
    member: TmdbCastMember,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier.width(124.dp),
        onClick = { }
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
            ) {
                val profileUrl = member.profileUrl?.trim().orEmpty()
                if (profileUrl.isNotBlank()) {
                    AsyncImage(
                        model = profileUrl,
                        contentDescription = null,
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
                                0f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.55f)
                            )
                        )
                )
            }

            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = member.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                member.character?.takeIf { it.isNotBlank() }?.let { character ->
                    Text(
                        text = character,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun TmdbTrailerCard(
    trailer: TmdbTrailer,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current
    ElevatedCard(
        modifier = modifier,
        onClick = { trailer.watchUrl?.let(uriHandler::openUri) }
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            ) {
                val thumbnail = trailer.thumbnailUrl?.trim().orEmpty()
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
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = trailer.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = trailer.type,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun TmdbProductionCard(
    entity: TmdbProductionEntity,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier.width(160.dp),
        onClick = { }
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                contentAlignment = Alignment.Center
            ) {
                val logo = entity.logoUrl?.trim().orEmpty()
                if (logo.isNotBlank()) {
                    AsyncImage(
                        model = logo,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.Center
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                }
            }

            Text(
                text = entity.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun tmdbFacts(
    tmdb: TmdbEnrichment,
    titleDetails: TmdbTitleDetails
): List<String> {
    val out = ArrayList<String>(12)

    tmdb.imdbId?.takeIf { it.isNotBlank() }?.let { out.add("IMDb: ${it.lowercase(Locale.US)}") }
    titleDetails.status?.takeIf { it.isNotBlank() }?.let { out.add("Status: $it") }

    when (titleDetails) {
        is TmdbMovieDetails -> {
            titleDetails.releaseDate?.takeIf { it.isNotBlank() }?.let { out.add("Release: $it") }
            titleDetails.runtimeMinutes?.takeIf { it > 0 }?.let { out.add("Runtime: ${it}m") }
            titleDetails.budget?.let { formatMoneyShort(it) }?.let { out.add("Budget: $it") }
            titleDetails.revenue?.let { formatMoneyShort(it) }?.let { out.add("Revenue: $it") }
        }

        is TmdbTvDetails -> {
            titleDetails.firstAirDate?.takeIf { it.isNotBlank() }?.let { out.add("First air: $it") }
            titleDetails.lastAirDate?.takeIf { it.isNotBlank() }?.let { out.add("Last air: $it") }
            titleDetails.numberOfSeasons?.takeIf { it > 0 }?.let { out.add("Seasons: $it") }
            titleDetails.numberOfEpisodes?.takeIf { it > 0 }?.let { out.add("Episodes: $it") }
            titleDetails.episodeRunTimeMinutes.firstOrNull()?.takeIf { it > 0 }?.let { out.add("Ep: ${it}m") }
            titleDetails.type?.takeIf { it.isNotBlank() }?.let { out.add("Type: $it") }
        }

        else -> Unit
    }

    titleDetails.originalLanguage?.takeIf { it.isNotBlank() }?.let { out.add("Lang: ${it}") }
    if (titleDetails.originCountries.isNotEmpty()) {
        out.add("Country: ${titleDetails.originCountries.take(3).joinToString()}")
    }

    return out
}

private fun formatMoneyShort(amount: Long): String? {
    if (amount <= 0L) return null
    val abs = amount.toDouble()
    val (value, suffix) =
        when {
            abs >= 1_000_000_000 -> abs / 1_000_000_000 to "B"
            abs >= 1_000_000 -> abs / 1_000_000 to "M"
            abs >= 1_000 -> abs / 1_000 to "K"
            else -> abs to ""
        }
    val formatted =
        if (value >= 10 || suffix.isEmpty()) {
            String.format(Locale.US, "%.0f", value)
        } else {
            String.format(Locale.US, "%.1f", value).removeSuffix(".0")
        }
    return "$$formatted$suffix"
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

    var textLayoutResult by remember(content) { mutableStateOf<TextLayoutResult?>(null) }
    val displayContent = remember(content, expanded, textLayoutResult) {
        val layout = textLayoutResult
        if (expanded) {
            buildAnnotatedString {
                append(content)
                append(" ")
                withStyle(SpanStyle(color = textColor.copy(alpha = 0.64f), fontWeight = FontWeight.Bold)) {
                    append("Show less")
                }
            }
        } else if (layout != null && layout.hasVisualOverflow) {
            val lineEnd = layout.getLineEnd(2, visibleOnly = true)
            buildAnnotatedString {
                append(content.substring(0, lineEnd).dropLast(12).trim())
                append("... ")
                withStyle(SpanStyle(color = textColor.copy(alpha = 0.9f), fontWeight = FontWeight.Bold)) {
                    append("Show more")
                }
            }
        } else {
            buildAnnotatedString { append(content) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { expanded = !expanded }
    ) {
        Text(
            text = displayContent,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            textAlign = textAlign,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (textLayoutResult == null) textLayoutResult = it }
        )
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

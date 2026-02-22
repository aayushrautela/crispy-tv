@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.crispy.tv.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun DetailsScreen(
    uiState: DetailsUiState,
    onBack: () -> Unit,
    onItemClick: (String) -> Unit,
    onRetry: () -> Unit,
    onSeasonSelected: (Int) -> Unit,
    onToggleWatchlist: () -> Unit,
    onToggleWatched: () -> Unit,
    onSetRating: (Int?) -> Unit
) {
    val details = uiState.details
    val listState = rememberLazyListState()
    val theming = rememberDetailsTheming(imageUrl = details?.backdropUrl ?: details?.posterUrl)
    val palette = theming.palette
    val detailsScheme = theming.colorScheme

    val trailerUrl =
        uiState.tmdbEnrichment
            ?.trailers
            ?.firstOrNull { it.watchUrl?.isNotBlank() == true && it.official && it.type.equals("Trailer", true) }
            ?.watchUrl
            ?: uiState.tmdbEnrichment
                ?.trailers
                ?.firstOrNull { it.watchUrl?.isNotBlank() == true && it.type.equals("Trailer", true) }
                ?.watchUrl
            ?: uiState.tmdbEnrichment
                ?.trailers
                ?.firstOrNull { it.watchUrl?.isNotBlank() == true }
                ?.watchUrl

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

    val snackbarHostState = remember { SnackbarHostState() }
    var lastSnackMessage by remember { mutableStateOf("") }
    LaunchedEffect(uiState.statusMessage) {
        val message = uiState.statusMessage.trim()
        if (message.isBlank()) return@LaunchedEffect
        if (details == null && uiState.isLoading) return@LaunchedEffect
        if (message == lastSnackMessage) return@LaunchedEffect

        lastSnackMessage = message
        snackbarHostState.showSnackbar(message)
    }

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
                    HeroSection(details = details, palette = palette, trailerUrl = trailerUrl)
                }

                item {
                    HeaderInfoSection(
                        details = details,
                        trailerUrl = trailerUrl,
                        isInWatchlist = uiState.isInWatchlist,
                        isWatched = uiState.isWatched,
                        isRated = uiState.isRated,
                        userRating = uiState.userRating,
                        isMutating = uiState.isMutating,
                        palette = palette,
                        onToggleWatchlist = onToggleWatchlist,
                        onToggleWatched = onToggleWatched,
                        onSetRating = onSetRating
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

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
    }
}

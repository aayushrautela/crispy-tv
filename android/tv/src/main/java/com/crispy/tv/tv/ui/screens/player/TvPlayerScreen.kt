package com.crispy.tv.tv.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.crispy.tv.tv.player.TvPlayerViewModel
import kotlinx.coroutines.delay

@Composable
fun TvPlayerScreen(
    viewModel: TvPlayerViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var chromeVisible by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> viewModel.onHostPaused()
                Lifecycle.Event.ON_RESUME -> viewModel.onHostResumed()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(chromeVisible, state.hasSource) {
        if (chromeVisible && state.hasSource) {
            delay(4000)
            chromeVisible = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusable()
            .onKeyEvent { event -> handleKey(event, viewModel) { chromeVisible = true } },
    ) {
        if (state.hasSource) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = false
                        player = viewModel.player
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        AnimatedVisibility(visible = chromeVisible || !state.hasSource) {
            PlayerChrome(state = state)
        }
    }
}

private fun handleKey(
    event: KeyEvent,
    viewModel: TvPlayerViewModel,
    wakeChrome: () -> Unit,
): Boolean {
    if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return false
    return when (event.key) {
        Key.DirectionCenter, Key.Enter, Key.Spacebar -> {
            wakeChrome()
            viewModel.playPause()
            true
        }
        Key.DirectionLeft -> {
            wakeChrome()
            viewModel.seekBy(-10)
            true
        }
        Key.DirectionRight -> {
            wakeChrome()
            viewModel.seekBy(10)
            true
        }
        Key.DirectionUp, Key.DirectionDown -> {
            wakeChrome()
            true
        }
        else -> false
    }
}

@Composable
private fun PlayerChrome(state: com.crispy.tv.tv.player.TvPlayerUiState) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 48.dp, vertical = 18.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.title.ifBlank { "Now playing" },
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                )
                state.episodeLabel?.let { label ->
                    Text(text = label, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        when {
            state.error != null -> ErrorCard(message = state.error!!)
            state.loading -> Text(
                text = "Preparing playback…",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 15.sp,
                modifier = Modifier.padding(start = 48.dp, bottom = 32.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ErrorCard(message: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(horizontal = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 22.dp, vertical = 14.dp),
    ) {
        Text(text = message, color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp)
    }
}

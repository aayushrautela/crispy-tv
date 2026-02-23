package com.crispy.tv.details

import android.annotation.SuppressLint
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.crispy.tv.BuildConfig
import com.crispy.tv.home.MediaDetails
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding

@Composable
internal fun HeroSection(
    details: MediaDetails?,
    palette: DetailsPaletteColors,
    trailerKey: String?,
    trailerWatchUrl: String?,
    showTrailer: Boolean,
    revealTrailer: Boolean,
    isTrailerPlaying: Boolean,
    isTrailerMuted: Boolean,
    onToggleTrailer: () -> Unit,
    onToggleTrailerMute: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val horizontalPadding = responsivePageHorizontalPadding()
    val heroHeight = (configuration.screenHeightDp.dp * 0.52f).coerceIn(340.dp, 520.dp)
    val uriHandler = LocalUriHandler.current

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

        var trailerFailed by remember(trailerKey) { mutableStateOf(false) }
        if (showTrailer && !trailerKey.isNullOrBlank()) {
            HeroYouTubeTrailerLayer(
                modifier = Modifier.fillMaxSize(),
                trailerKey = trailerKey,
                reveal = revealTrailer,
                isPlaying = isTrailerPlaying,
                isMuted = isTrailerMuted,
                onError = { trailerFailed = true }
            )
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

        val hasTrailer = !trailerKey.isNullOrBlank()
        if (hasTrailer) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 20.dp, end = 16.dp)
                    .clip(MaterialTheme.shapes.extraLarge)
                    .clickable { onToggleTrailerMute() },
                color = Color.Black.copy(alpha = 0.34f),
                contentColor = Color.White
            ) {
                Icon(
                    imageVector = if (isTrailerMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                    contentDescription = if (isTrailerMuted) "Unmute trailer" else "Mute trailer",
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        val resolvedTrailerWatchUrl = trailerWatchUrl?.trim().takeIf { !it.isNullOrBlank() }
        if (hasTrailer) {
            val isPlaying = isTrailerPlaying
            val icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow
            val label = if (trailerFailed && resolvedTrailerWatchUrl != null) "Open trailer" else if (isPlaying) "Pause" else "Trailer"
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 160.dp)
                    .clip(MaterialTheme.shapes.extraLarge)
                    .clickable {
                        if (trailerFailed && resolvedTrailerWatchUrl != null) {
                            uriHandler.openUri(resolvedTrailerWatchUrl)
                        } else {
                            onToggleTrailer()
                        }
                    },
                color = Color.Black.copy(alpha = 0.34f),
                contentColor = Color.White
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(label, style = MaterialTheme.typography.labelLarge)
                }
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

@Composable
private fun HeroYouTubeTrailerLayer(
    modifier: Modifier,
    trailerKey: String,
    reveal: Boolean,
    isPlaying: Boolean,
    isMuted: Boolean,
    onError: () -> Unit,
) {
    val clientIdentityUrl = remember { "https://${BuildConfig.APPLICATION_ID}" }
    val embedUrl = remember(trailerKey, clientIdentityUrl) { buildYoutubeEmbedUrl(trailerKey, clientIdentityUrl) }
    val headers = remember(clientIdentityUrl) { mapOf("Referer" to clientIdentityUrl) }
    val latestOnError by rememberUpdatedState(onError)
    val latestMuted by rememberUpdatedState(isMuted)
    val latestPlaying by rememberUpdatedState(isPlaying)

    val revealMaskAlpha by animateFloatAsState(
        targetValue = if (reveal) 0f else 1f,
        label = "hero_trailer_reveal_mask"
    )

    val context = androidx.compose.ui.platform.LocalContext.current
    val webView = remember(trailerKey) {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = false
            isLongClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

            @SuppressLint("SetJavaScriptEnabled")
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true

            // Treat as background video layer.
            setOnTouchListener { _, _ -> true }

            webViewClient =
                object : WebViewClient() {
                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        if (request.isForMainFrame) {
                            latestOnError()
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        errorResponse: WebResourceResponse,
                    ) {
                        if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                            latestOnError()
                        }
                    }

                    override fun onRenderProcessGone(
                        view: WebView,
                        detail: RenderProcessGoneDetail,
                    ): Boolean {
                        latestOnError()
                        return true
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        // Best effort: apply current state after load.
                        applyMute(view, latestMuted)
                        applyPlayPause(view, latestPlaying)
                    }
                }
        }
    }

    DisposableEffect(webView) {
        onDispose {
            runCatching { webView.stopLoading() }
            runCatching { webView.loadUrl("about:blank") }
            runCatching { webView.destroy() }
        }
    }

    LaunchedEffect(webView, embedUrl) {
        runCatching {
            webView.loadUrl(embedUrl, headers)
        }.onFailure {
            latestOnError()
        }
    }

    LaunchedEffect(webView, isMuted) {
        applyMute(webView, isMuted)
    }

    LaunchedEffect(webView, isPlaying) {
        applyPlayPause(webView, isPlaying)
    }

    Box(modifier = modifier.clipToBounds()) {
        AndroidView(
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer(scaleX = 1.35f, scaleY = 1.35f),
            factory = { webView },
            update = {}
        )

        if (revealMaskAlpha > 0.001f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = revealMaskAlpha))
            )
        }
    }
}

private fun buildYoutubeEmbedUrl(videoId: String, clientIdentityUrl: String): String {
    val id = videoId.trim()
    // YouTube requires a client identity (Referer/origin). For WebView, Google recommends using
    // an HTTPS URL whose domain is your app ID (package name).
    return "https://www.youtube.com/embed/$id" +
        "?autoplay=1" +
        "&controls=0" +
        "&showinfo=0" +
        "&rel=0" +
        "&loop=1" +
        "&playlist=$id" +
        "&modestbranding=1" +
        "&playsinline=1" +
        "&mute=1" +
        "&enablejsapi=1" +
        "&origin=$clientIdentityUrl"
}

private fun applyMute(webView: WebView, muted: Boolean) {
    val m = if (muted) "true" else "false"
    webView.evaluateJavascript(
        "(function(){var v=document.querySelector('video'); if(v){v.muted=$m; if($m){v.volume=0;} }})();",
        null
    )
}

private fun applyPlayPause(webView: WebView, playing: Boolean) {
    val p = if (playing) "true" else "false"
    webView.evaluateJavascript(
        "(function(){var v=document.querySelector('video'); if(v){ if($p){v.play();} else {v.pause();} }})();",
        null
    )
}

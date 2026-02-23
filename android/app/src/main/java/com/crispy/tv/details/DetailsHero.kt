package com.crispy.tv.details

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import kotlinx.coroutines.delay

@Composable
internal fun HeroSection(
    details: MediaDetails?,
    palette: DetailsPaletteColors,
    trailerKey: String?,
    trailerWatchUrl: String?,
    showTrailer: Boolean,
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

        val hasTrailer = !trailerKey.isNullOrBlank()
        var trailerFailed by remember(trailerKey) { mutableStateOf(false) }
        var trailerPlaybackConfirmed by remember(trailerKey) { mutableStateOf(false) }

        val shouldAttemptPlayback = showTrailer && hasTrailer && isTrailerPlaying && !trailerFailed
        val latestShouldAttemptPlayback by rememberUpdatedState(shouldAttemptPlayback)

        LaunchedEffect(trailerKey, shouldAttemptPlayback) {
            if (!shouldAttemptPlayback) {
                trailerPlaybackConfirmed = false
                return@LaunchedEffect
            }

            trailerPlaybackConfirmed = false
            delay(8000)
            if (!trailerPlaybackConfirmed) {
                trailerFailed = true
            }
        }

        if (showTrailer && hasTrailer) {
            HeroYouTubeTrailerLayer(
                modifier = Modifier.fillMaxSize(),
                trailerKey = trailerKey,
                shouldPlay = shouldAttemptPlayback,
                isMuted = isTrailerMuted,
                onPlaybackState = { state, _ ->
                    if (state == 1 && latestShouldAttemptPlayback) {
                        trailerPlaybackConfirmed = true
                    }
                },
                onError = { trailerFailed = true }
            )
        }

        val coverAlpha by animateFloatAsState(
            targetValue = if (shouldAttemptPlayback && trailerPlaybackConfirmed) 0f else 1f,
            animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
            label = "hero_trailer_cover_alpha",
        )

        if (showTrailer && hasTrailer && coverAlpha > 0.001f) {
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(alpha = coverAlpha),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(alpha = coverAlpha),
                    color = palette.pillBackground,
                ) {}
            }
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

        if (showTrailer && hasTrailer && !trailerFailed) {
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
                    imageVector = if (isTrailerMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = if (isTrailerMuted) "Unmute trailer" else "Mute trailer",
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        val resolvedTrailerWatchUrl = trailerWatchUrl?.trim().takeIf { !it.isNullOrBlank() }
        if (hasTrailer) {
            val isActuallyPlaying = isTrailerPlaying && trailerPlaybackConfirmed && !trailerFailed
            val icon = if (isActuallyPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow
            val label = if (trailerFailed && resolvedTrailerWatchUrl != null) "Open trailer" else if (isActuallyPlaying) "Pause" else "Trailer"
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
    shouldPlay: Boolean,
    isMuted: Boolean,
    onPlaybackState: (state: Int, timeSeconds: Double) -> Unit,
    onError: () -> Unit,
) {
    val clientIdentityOrigin = remember { "https://${BuildConfig.APPLICATION_ID}" }
    val baseUrl = remember(clientIdentityOrigin) { "$clientIdentityOrigin/" }
    val playerHtml = remember(trailerKey, clientIdentityOrigin) { buildYoutubeTrailerHtml(trailerKey, clientIdentityOrigin) }
    val latestOnError by rememberUpdatedState(onError)
    val latestOnPlaybackState by rememberUpdatedState(onPlaybackState)
    val latestMuted by rememberUpdatedState(isMuted)
    val latestShouldPlay by rememberUpdatedState(shouldPlay)

    val context = androidx.compose.ui.platform.LocalContext.current
    val webView = remember(trailerKey) {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = false
            isLongClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false

            @SuppressLint("SetJavaScriptEnabled")
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true

            CookieManager.getInstance().setAcceptCookie(true)
            runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(this, true) }

            // Treat as background video layer.
            setOnTouchListener { _, _ -> true }

            val mainHandler = Handler(Looper.getMainLooper())
            addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun onReady() {
                        mainHandler.post {
                            applyMute(this@apply, latestMuted)
                            applyPlayPause(this@apply, latestShouldPlay)
                        }
                    }

                    @JavascriptInterface
                    fun onState(state: Int, timeSeconds: Double) {
                        mainHandler.post {
                            latestOnPlaybackState(state, timeSeconds)
                        }
                    }

                    @JavascriptInterface
                    fun onError(code: Int) {
                        mainHandler.post {
                            latestOnError()
                        }
                    }
                },
                "CrispyBridge",
            )

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
                        applyPlayPause(view, latestShouldPlay)
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

    LaunchedEffect(webView, baseUrl, playerHtml) {
        runCatching {
            webView.loadDataWithBaseURL(
                baseUrl,
                playerHtml,
                "text/html",
                "utf-8",
                null
            )
        }.onFailure {
            latestOnError()
        }
    }

    LaunchedEffect(webView, isMuted) {
        applyMute(webView, isMuted)
    }

    LaunchedEffect(webView, shouldPlay) {
        applyPlayPause(webView, shouldPlay)
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
    }
}

private fun buildYoutubeTrailerHtml(videoId: String, origin: String): String {
    val id = escapeJsString(videoId.trim())
    val o = escapeJsString(origin.trim())
    return (
        "<!DOCTYPE html>" +
            "<html>" +
            "<head>" +
            "<meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=1.0'/>" +
            "<style>" +
            "html,body{margin:0;padding:0;background:#000;overflow:hidden;}" +
            "#player{position:absolute;top:0;left:0;width:100%;height:100%;}" +
            "</style>" +
            "</head>" +
            "<body>" +
            "<div id='player'></div>" +
            "<script>" +
            "(function(){" +
            "var VIDEO_ID='$id';" +
            "var ORIGIN='$o';" +
            "var desiredMuted=true;" +
            "var desiredPlaying=false;" +
            "var player=null;" +
            "function safeCall(fn){try{fn();}catch(e){}}" +
            "function notifyReady(){safeCall(function(){CrispyBridge.onReady();});}" +
            "function notifyState(state){" +
            "  var t=0;" +
            "  safeCall(function(){ if(player&&player.getCurrentTime){ t=player.getCurrentTime(); } });" +
            "  safeCall(function(){ CrispyBridge.onState(state,t); });" +
            "}" +
            "function notifyError(code){safeCall(function(){CrispyBridge.onError(code);});}" +
            "window.__crispyTrailer={" +
            "  setMuted:function(m){ desiredMuted=!!m; if(!player){return;} safeCall(function(){ if(desiredMuted){ player.mute(); player.setVolume(0);} else { player.setVolume(100); player.unMute(); } }); }," +
            "  setPlaying:function(p){ desiredPlaying=!!p; if(!player){return;} safeCall(function(){ if(desiredPlaying){ player.playVideo(); } else { player.pauseVideo(); } }); }" +
            "};" +
            "window.onYouTubeIframeAPIReady=function(){" +
            "  player=new YT.Player('player',{" +
            "    width:'100%',height:'100%',videoId:VIDEO_ID," +
            "    playerVars:{autoplay:0,controls:0,rel:0,modestbranding:1,playsinline:1,mute:1,loop:1,playlist:VIDEO_ID,enablejsapi:1,origin:ORIGIN}," +
            "    events:{" +
            "      onReady:function(){" +
            "        safeCall(function(){ if(desiredMuted){ player.mute(); player.setVolume(0);} else { player.setVolume(100); player.unMute(); } });" +
            "        safeCall(function(){ if(desiredPlaying){ player.playVideo(); } });" +
            "        notifyReady();" +
            "      }," +
            "      onStateChange:function(e){ notifyState(e.data); }," +
            "      onError:function(e){ notifyError(e.data); }" +
            "    }" +
            "  });" +
            "};" +
            "var tag=document.createElement('script');" +
            "tag.src='https://www.youtube.com/iframe_api';" +
            "document.head.appendChild(tag);" +
            "})();" +
            "</script>" +
            "</body>" +
            "</html>"
    )
}

private fun escapeJsString(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
}

private fun applyMute(webView: WebView, muted: Boolean) {
    val m = if (muted) "true" else "false"
    webView.evaluateJavascript(
        "try{window.__crispyTrailer && window.__crispyTrailer.setMuted($m);}catch(e){}",
        null
    )
}

private fun applyPlayPause(webView: WebView, playing: Boolean) {
    val p = if (playing) "true" else "false"
    webView.evaluateJavascript(
        "try{window.__crispyTrailer && window.__crispyTrailer.setPlaying($p);}catch(e){}",
        null
    )
}

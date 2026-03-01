package com.crispy.tv.details

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebChromeClient
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.crispy.tv.home.MediaDetails
import com.crispy.tv.ui.components.skeletonElement
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding

@Composable
internal fun HeroSection(
    details: MediaDetails?,
    palette: DetailsPaletteColors,
    trailerKey: String?,
    showTrailer: Boolean,
    isTrailerPlaying: Boolean,
    isTrailerMuted: Boolean,
    onToggleTrailer: () -> Unit,
    onToggleTrailerMute: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val horizontalPadding = responsivePageHorizontalPadding()
    val heroHeight = (configuration.screenHeightDp.dp * 0.52f).coerceIn(340.dp, 520.dp)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(heroHeight)
            .clipToBounds()
    ) {
        val heightPx = with(LocalDensity.current) { maxHeight.toPx() }

        if (details == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .skeletonElement(
                        shape = androidx.compose.ui.graphics.RectangleShape,
                        color = DetailsSkeletonColors.Base
                    )
            ) {
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

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = horizontalPadding)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.64f)
                            .height(38.dp)
                            .skeletonElement(color = DetailsSkeletonColors.Elevated)
                    )
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
        var trailerIsPlaying by remember(trailerKey) { mutableStateOf(false) }
        val shouldAttemptPlayback = showTrailer && hasTrailer && isTrailerPlaying

        if (showTrailer && hasTrailer) {
            HeroYouTubeTrailerLayer(
                modifier = Modifier.fillMaxSize(),
                trailerKey = trailerKey,
                shouldPlay = shouldAttemptPlayback,
                isMuted = isTrailerMuted,
                onPlaybackState = { state, _ -> trailerIsPlaying = state == 1 },
            )
        }

        val coverAlpha by animateFloatAsState(
            targetValue = if (shouldAttemptPlayback && trailerIsPlaying) 0f else 1f,
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

        if (shouldAttemptPlayback && trailerIsPlaying) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
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

        if (hasTrailer) {
            val isActuallyPlaying = isTrailerPlaying && trailerIsPlaying
            val icon = if (isActuallyPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow
            val label = if (isActuallyPlaying) "Pause" else "Trailer"
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 160.dp)
                    .clip(MaterialTheme.shapes.extraLarge)
                    .clickable { onToggleTrailer() },
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
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val origin = remember { "https://${context.packageName}" }
    val embedUrl = remember(trailerKey, origin) { buildYouTubeEmbedUrl(trailerKey, origin) }
    val latestOnPlaybackState by rememberUpdatedState(onPlaybackState)
    val latestMuted by rememberUpdatedState(isMuted)
    val latestShouldPlay by rememberUpdatedState(shouldPlay)

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

            CookieManager.getInstance().setAcceptCookie(true)
            runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(this, true) }

            setOnTouchListener { _, _ -> true }

            val mainHandler = Handler(Looper.getMainLooper())
            addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun onReady() {
                        Log.d("TrailerDbg", "CrispyBridge.onReady() called")
                        mainHandler.post {
                            applyMute(this@apply, latestMuted)
                            applyPlayPause(this@apply, latestShouldPlay)
                        }
                    }

                    @JavascriptInterface
                    fun onState(state: Int, timeSeconds: Double) {
                        Log.d("TrailerDbg", "CrispyBridge.onState($state, $timeSeconds)")
                        mainHandler.post { latestOnPlaybackState(state, timeSeconds) }
                    }

                    @JavascriptInterface
                    fun onError(code: Int) {
                        Log.d("TrailerDbg", "CrispyBridge.onError($code)")
                    }
                },
                "CrispyBridge",
            )

            // Required for HTML5 <video> playback pipeline in Android WebView.
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    Log.d("TrailerDbg", "JS[${msg.lineNumber()}]: ${msg.message()}")
                    return true
                }
            }

            webViewClient =
                object : WebViewClient() {
                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        Log.d("TrailerDbg", "onReceivedError mainFrame=${request.isForMainFrame} url=${request.url} code=${error.errorCode} desc=${error.description}")
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        errorResponse: WebResourceResponse,
                    ) {
                        Log.d("TrailerDbg", "onReceivedHttpError mainFrame=${request.isForMainFrame} url=${request.url} status=${errorResponse.statusCode}")
                    }

                    override fun onRenderProcessGone(
                        view: WebView,
                        detail: RenderProcessGoneDetail,
                    ): Boolean {
                        Log.d("TrailerDbg", "onRenderProcessGone")
                        return true
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        Log.d("TrailerDbg", "onPageFinished url=$url")
                        if (url == null || url.startsWith("about:")) return
                        injectBridge(view)
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
        Log.d("TrailerDbg", "loadUrl embedUrl=$embedUrl origin=$origin")
        runCatching {
            webView.loadUrl(embedUrl, mapOf("Referer" to origin))
        }.onFailure { Log.d("TrailerDbg", "loadUrl failed: $it") }
    }

    LaunchedEffect(webView, isMuted) { applyMute(webView, isMuted) }
    LaunchedEffect(webView, shouldPlay) { applyPlayPause(webView, shouldPlay) }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { webView },
            update = {}
        )
    }
}

private fun buildYouTubeEmbedUrl(videoId: String, origin: String): String {
    val id = videoId.trim()
    return "https://www.youtube.com/embed/$id" +
        "?autoplay=1&controls=0&rel=0&modestbranding=1&playsinline=1" +
        "&mute=1&loop=1&playlist=$id&enablejsapi=1" +
        "&iv_load_policy=3&showinfo=0&fs=0&disablekb=1" +
        "&origin=$origin"
}

/**
 * Injected after the YouTube embed page loads. Hides YouTube chrome via CSS
 * and controls the raw HTML5 <video> element directly (bypassing YouTube's
 * player JS API).
 *
 * Re-queries `document.querySelector('video')` every tick so the bridge
 * survives YouTube's internal error-recovery which can replace the element.
 */
private fun injectBridge(view: WebView) {
    //language=JavaScript
    val js = """
        (function() {
            if (window.__crispyInjected) return;
            window.__crispyInjected = true;
            console.log('[CrispyTrailer] bridge injecting');

            var style = document.createElement('style');
            style.textContent = [
                /* page baseline */
                'html, body { background:black!important; overflow:hidden!important; margin:0!important; padding:0!important; }',
                /* hide YouTube chrome */
                '.ytp-chrome-top, .ytp-chrome-bottom, .ytp-watermark,',
                '.ytp-pause-overlay, .ytp-endscreen-content, .ytp-ce-element,',
                '.ytp-gradient-top, .ytp-gradient-bottom, .ytp-spinner,',
                '.ytp-contextmenu, .ytp-show-cards-title, .ytp-paid-content-overlay,',
                '.ytp-impression-link, .iv-branding, .annotation,',
                '.ytp-chrome-controls { display:none!important; opacity:0!important; }',
                /* hide error / loading overlays left over from failed first stream attempt */
                '.ytp-error, .ytp-error-content-wrap, .ytp-error-content,',
                '.ytp-offline-slate, .ytp-offline-slate-bar,',
                '.html5-video-info-panel { display:none!important; }'
            ].join('\n');
            document.head.appendChild(style);

            var video = null;
            var lastState = -2;

            function safe(fn) { try { fn(); } catch(e) { console.log('[CrispyTrailer] safe error: ' + e); } }

            function mapState() {
                if (!video) return -1;
                if (video.ended) return 0;
                if (!video.paused && video.readyState >= 3) return 1;
                if (video.paused) return 2;
                return 3;
            }

            window.__crispyTrailer = {
                setMuted: function(m) {
                    if (!video) return;
                    safe(function() { video.muted = !!m; video.volume = m ? 0 : 1; });
                },
                setPlaying: function(p) {
                    if (!video) return;
                    safe(function() { if (p) video.play(); else video.pause(); });
                }
            };

            setInterval(function() {
                var v = document.querySelector('video');
                if (v && v !== video) {
                    video = v;
                    console.log('[CrispyTrailer] <video> found, wiring state');
                    v.addEventListener('error', function() {
                        safe(function() { CrispyBridge.onError(video && video.error ? video.error.code : -1); });
                    });
                    safe(function() { CrispyBridge.onReady(); });
                }
                var s = mapState();
                var t = (video && video.currentTime) || 0;
                if (s !== lastState) {
                    lastState = s;
                    safe(function() { CrispyBridge.onState(s, t); });
                }
            }, 250);
        })();
    """.trimIndent()
    view.evaluateJavascript(js, null)
}

private fun applyMute(webView: WebView, muted: Boolean) {
    val m = if (muted) "true" else "false"
    webView.evaluateJavascript(
        "try{__crispyTrailer&&__crispyTrailer.setMuted($m)}catch(e){}",
        null
    )
}

private fun applyPlayPause(webView: WebView, playing: Boolean) {
    val p = if (playing) "true" else "false"
    webView.evaluateJavascript(
        "try{__crispyTrailer&&__crispyTrailer.setPlaying($p)}catch(e){}",
        null
    )
}

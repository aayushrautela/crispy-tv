package com.crispy.tv.playerui

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.nativeengine.playback.PlaybackSource
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.ui.theme.CrispyRewriteTheme
import org.json.JSONObject

class PlayerActivity : ComponentActivity() {
    private var isInPictureInPictureModeState by mutableStateOf(false)
    private var pipEnabled: Boolean = false
    private var pipSourceRect: Rect? = null
    private var pipAspectRatio: Rational? = null

    private val notificationsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        isInPictureInPictureModeState = isInPictureInPictureMode

        val playbackUrl = intent.getStringExtra(EXTRA_PLAYBACK_URL).orEmpty()
        if (playbackUrl.isBlank()) {
            finish()
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val subtitle = intent.getStringExtra(EXTRA_SUBTITLE)?.trim()?.ifBlank { null }
        val artworkUrl = intent.getStringExtra(EXTRA_ARTWORK_URL)?.trim()?.ifBlank { null }
        val launchSnapshot = PlayerLaunchSnapshot.fromJsonString(intent.getStringExtra(EXTRA_LAUNCH_SNAPSHOT))
        val playbackSource = parsePlaybackSourceFromIntent(intent)

        val identity = parseIdentityFromIntent(intent, title)
        val restorePlaybackIntent =
            Intent(intent).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            )
        val sessionViewModel =
            ViewModelProvider(
                this,
                PlayerSessionViewModel.factory(
                    appContext = applicationContext,
                    playbackSource = playbackSource,
                    title = title,
                    subtitle = subtitle,
                    artworkUrl = artworkUrl,
                    identity = identity,
                    launchSnapshot = launchSnapshot,
                    restorePlaybackIntent = restorePlaybackIntent,
                ),
            )[PlayerSessionViewModel::class.java]

        Log.d(
            TAG,
            "onCreate savedInstanceState=${savedInstanceState != null} title=$title hasIdentity=${identity != null} isInPip=$isInPictureInPictureModeState",
        )

        maybeRequestNotificationPermission()
        applyPlayerWindowPolicy()
        updatePictureInPictureConfig(PictureInPictureConfig())

        setContent {
            CrispyRewriteTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PlayerRoute(
                        session = sessionViewModel,
                        isInPictureInPictureMode = isInPictureInPictureModeState,
                        onPictureInPictureConfigChanged = ::updatePictureInPictureConfig,
                        onBack = { finish() },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart isInPip=$isInPictureInPictureModeState finishing=$isFinishing")
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume isInPip=$isInPictureInPictureModeState")
    }

    override fun onPause() {
        Log.d(TAG, "onPause isInPip=$isInPictureInPictureModeState finishing=$isFinishing")
        super.onPause()
    }

    override fun onStop() {
        Log.d(
            TAG,
            "onStop isInPip=$isInPictureInPictureModeState finishing=$isFinishing changingConfigurations=$isChangingConfigurations",
        )
        super.onStop()
    }

    override fun onDestroy() {
        Log.d(
            TAG,
            "onDestroy isInPip=$isInPictureInPictureModeState finishing=$isFinishing changingConfigurations=$isChangingConfigurations",
        )
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.d(TAG, "onWindowFocusChanged hasFocus=$hasFocus isInPip=$isInPictureInPictureModeState")
        if (hasFocus && !isInPictureInPictureModeState) {
            applyPlayerWindowPolicy()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        Log.d(
            TAG,
            "onUserLeaveHint sdk=${Build.VERSION.SDK_INT} pipEnabled=$pipEnabled isInPip=$isInPictureInPictureModeState",
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            enterPictureInPictureIfPossible()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        Log.d(
            TAG,
            "onPictureInPictureModeChanged isInPip=$isInPictureInPictureMode newConfig=$newConfig",
        )
        isInPictureInPictureModeState = isInPictureInPictureMode
        if (!isInPictureInPictureMode) {
            applyPlayerWindowPolicy()
        }
    }

    private fun applyPlayerWindowPolicy() {
        Log.d(TAG, "applyPlayerWindowPolicy isInPip=$isInPictureInPictureModeState")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attrs
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        if (isInPictureInPictureModeState) {
            return
        }

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun updatePictureInPictureConfig(config: PictureInPictureConfig) {
        pipEnabled = config.enabled
        pipSourceRect = config.sourceRect
        pipAspectRatio = config.aspectRatio

        Log.d(
            TAG,
            "updatePictureInPictureConfig enabled=${config.enabled} sourceRect=${config.sourceRect} aspectRatio=${config.aspectRatio}",
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setPictureInPictureParams(buildPictureInPictureParams())
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun enterPictureInPictureIfPossible() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.d(TAG, "enterPictureInPictureIfPossible skipped reason=sdk")
            return
        }
        if (!pipEnabled || isInPictureInPictureModeState || isFinishing || isDestroyed) {
            Log.d(
                TAG,
                "enterPictureInPictureIfPossible skipped pipEnabled=$pipEnabled isInPip=$isInPictureInPictureModeState finishing=$isFinishing destroyed=$isDestroyed",
            )
            return
        }
        runCatching {
            enterPictureInPictureMode(buildPictureInPictureParams())
        }.onSuccess {
            Log.d(TAG, "enterPictureInPictureIfPossible success")
        }.onFailure { error ->
            Log.w(TAG, "enterPictureInPictureIfPossible failed", error)
        }
    }

    private fun buildPictureInPictureParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()

        pipSourceRect?.let(builder::setSourceRectHint)
        pipAspectRatio
            ?.takeIf { it.numerator > 0 && it.denominator > 0 }
            ?.let(builder::setAspectRatio)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(pipEnabled)
            builder.setSeamlessResizeEnabled(true)
        }

        return builder.build()
    }

    companion object {
        private const val TAG = "PlayerActivity"
        private const val EXTRA_PLAYBACK_URL = "extra_playback_url"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_SUBTITLE = "extra_subtitle"
        private const val EXTRA_ARTWORK_URL = "extra_artwork_url"
        private const val EXTRA_LAUNCH_SNAPSHOT = "extra_launch_snapshot"
        private const val EXTRA_PLAYBACK_HEADERS_JSON = "extra_playback_headers_json"

        private const val EXTRA_IMDB_ID = "extra_imdb_id"
        private const val EXTRA_CONTENT_ID = "extra_content_id"
        private const val EXTRA_MEDIA_KEY = "extra_media_key"
        private const val EXTRA_MEDIA_TYPE = "extra_media_type"
        private const val EXTRA_SEASON = "extra_season"
        private const val EXTRA_EPISODE = "extra_episode"
        private const val EXTRA_YEAR = "extra_year"
        private const val EXTRA_SHOW_TITLE = "extra_show_title"
        private const val EXTRA_SHOW_YEAR = "extra_show_year"
        private const val EXTRA_PROVIDER = "extra_provider"
        private const val EXTRA_PROVIDER_ID = "extra_provider_id"
        private const val EXTRA_PARENT_MEDIA_TYPE = "extra_parent_media_type"
        private const val EXTRA_PARENT_PROVIDER = "extra_parent_provider"
        private const val EXTRA_PARENT_PROVIDER_ID = "extra_parent_provider_id"
        private const val EXTRA_ABSOLUTE_EPISODE_NUMBER = "extra_absolute_episode_number"

        fun intent(
            context: Context,
            playbackUrl: String,
            playbackHeaders: Map<String, String> = emptyMap(),
            title: String,
            identity: PlaybackIdentity,
            subtitle: String? = null,
            artworkUrl: String? = null,
            launchSnapshot: PlayerLaunchSnapshot? = null,
        ): Intent {
            return Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_PLAYBACK_URL, playbackUrl)
                .putExtra(EXTRA_PLAYBACK_HEADERS_JSON, headersToJson(playbackHeaders))
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_SUBTITLE, subtitle)
                .putExtra(EXTRA_ARTWORK_URL, artworkUrl)
.putExtra(EXTRA_LAUNCH_SNAPSHOT, launchSnapshot?.toJsonString())
        .putExtra(EXTRA_MEDIA_KEY, identity.mediaKey)
        .putExtra(EXTRA_MEDIA_TYPE, identity.contentType.name)
        .putExtra(EXTRA_SEASON, identity.season ?: -1)
        .putExtra(EXTRA_EPISODE, identity.episode ?: -1)
        .putExtra(EXTRA_YEAR, identity.year ?: -1)
        .putExtra(EXTRA_SHOW_TITLE, identity.showTitle)
        .putExtra(EXTRA_SHOW_YEAR, identity.showYear ?: -1)
        .putExtra(EXTRA_PARENT_MEDIA_TYPE, identity.parentMediaType)
        .putExtra(EXTRA_ABSOLUTE_EPISODE_NUMBER, identity.absoluteEpisodeNumber ?: -1)
    }

        private fun parseIdentityFromIntent(intent: Intent, title: String): PlaybackIdentity? {
            val mediaTypeName = intent.getStringExtra(EXTRA_MEDIA_TYPE).orEmpty().trim()
            val contentType = runCatching { MetadataLabMediaType.valueOf(mediaTypeName) }.getOrNull()
                ?: return null

            val contentId = intent.getStringExtra(EXTRA_CONTENT_ID)?.trim()?.ifBlank { null }
            val mediaKey = intent.getStringExtra(EXTRA_MEDIA_KEY)?.trim()?.ifBlank { null }
            val imdbId = intent.getStringExtra(EXTRA_IMDB_ID)?.trim()?.ifBlank { null }
            val season = intent.getIntExtra(EXTRA_SEASON, -1).takeIf { it > 0 }
            val episode = intent.getIntExtra(EXTRA_EPISODE, -1).takeIf { it > 0 }
            val year = intent.getIntExtra(EXTRA_YEAR, -1).takeIf { it > 0 }
            val showTitle = intent.getStringExtra(EXTRA_SHOW_TITLE)?.trim()?.ifBlank { null }
            val showYear = intent.getIntExtra(EXTRA_SHOW_YEAR, -1).takeIf { it > 0 }
            val provider = intent.getStringExtra(EXTRA_PROVIDER)?.trim()?.ifBlank { null }
            val providerId = intent.getStringExtra(EXTRA_PROVIDER_ID)?.trim()?.ifBlank { null }
            val parentMediaType = intent.getStringExtra(EXTRA_PARENT_MEDIA_TYPE)?.trim()?.ifBlank { null }
            val parentProvider = intent.getStringExtra(EXTRA_PARENT_PROVIDER)?.trim()?.ifBlank { null }
            val parentProviderId = intent.getStringExtra(EXTRA_PARENT_PROVIDER_ID)?.trim()?.ifBlank { null }
            val absoluteEpisodeNumber = intent.getIntExtra(EXTRA_ABSOLUTE_EPISODE_NUMBER, -1).takeIf { it > 0 }

return PlaybackIdentity(
            mediaKey = mediaKey,
            tmdbId = null,
            contentType = contentType,
            season = season,
            episode = episode,
            title = title,
            year = year,
            showTitle = showTitle,
            showYear = showYear,
            parentMediaType = parentMediaType,
            absoluteEpisodeNumber = absoluteEpisodeNumber,
        )
        }

        private fun parsePlaybackSourceFromIntent(intent: Intent): PlaybackSource {
            val url = intent.getStringExtra(EXTRA_PLAYBACK_URL).orEmpty()
            val headersJson = intent.getStringExtra(EXTRA_PLAYBACK_HEADERS_JSON).orEmpty().trim()
            if (headersJson.isBlank()) {
                return PlaybackSource(url = url)
            }

            val headers =
                runCatching {
                    val json = JSONObject(headersJson)
                    buildMap {
                        val iterator = json.keys()
                        while (iterator.hasNext()) {
                            val key = iterator.next()?.trim().orEmpty()
                            if (key.isBlank()) continue
                            val value = json.optString(key).trim()
                            if (value.isBlank()) continue
                            put(key, value)
                        }
                    }
                }.getOrDefault(emptyMap())
            return PlaybackSource(url = url, headers = headers)
        }

        private fun headersToJson(headers: Map<String, String>): String? {
            if (headers.isEmpty()) return null
            return JSONObject().apply {
                headers.forEach { (key, value) ->
                    if (key.isNotBlank() && value.isNotBlank()) {
                        put(key, value)
                    }
                }
            }.takeIf { it.length() > 0 }?.toString()
        }
    }
}

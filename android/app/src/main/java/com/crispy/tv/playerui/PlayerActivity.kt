package com.crispy.tv.playerui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.crispy.tv.player.MetadataLabMediaType
import com.crispy.tv.player.PlaybackIdentity
import com.crispy.tv.ui.theme.CrispyRewriteTheme

class PlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        val playbackUrl = intent.getStringExtra(EXTRA_PLAYBACK_URL).orEmpty()
        if (playbackUrl.isBlank()) {
            finish()
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        val identity = parseIdentityFromIntent(intent, title)

        applyPlayerWindowPolicy()

        setContent {
            CrispyRewriteTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PlayerRoute(
                        playbackUrl = playbackUrl,
                        title = title,
                        identity = identity,
                        onBack = { finish() },
                    )
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyPlayerWindowPolicy()
        }
    }

    private fun applyPlayerWindowPolicy() {
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

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    companion object {
        private const val EXTRA_PLAYBACK_URL = "extra_playback_url"
        private const val EXTRA_TITLE = "extra_title"

        private const val EXTRA_IMDB_ID = "extra_imdb_id"
        private const val EXTRA_TMDB_ID = "extra_tmdb_id"
        private const val EXTRA_MEDIA_TYPE = "extra_media_type"
        private const val EXTRA_SEASON = "extra_season"
        private const val EXTRA_EPISODE = "extra_episode"
        private const val EXTRA_YEAR = "extra_year"
        private const val EXTRA_SHOW_TITLE = "extra_show_title"
        private const val EXTRA_SHOW_YEAR = "extra_show_year"

        fun intent(context: Context, playbackUrl: String, title: String, identity: PlaybackIdentity): Intent {
            return Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_PLAYBACK_URL, playbackUrl)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_IMDB_ID, identity.imdbId)
                .putExtra(EXTRA_TMDB_ID, identity.tmdbId ?: -1)
                .putExtra(EXTRA_MEDIA_TYPE, identity.contentType.name)
                .putExtra(EXTRA_SEASON, identity.season ?: -1)
                .putExtra(EXTRA_EPISODE, identity.episode ?: -1)
                .putExtra(EXTRA_YEAR, identity.year ?: -1)
                .putExtra(EXTRA_SHOW_TITLE, identity.showTitle)
                .putExtra(EXTRA_SHOW_YEAR, identity.showYear ?: -1)
        }

        private fun parseIdentityFromIntent(intent: Intent, title: String): PlaybackIdentity? {
            val mediaTypeName = intent.getStringExtra(EXTRA_MEDIA_TYPE).orEmpty().trim()
            val contentType = runCatching { MetadataLabMediaType.valueOf(mediaTypeName) }.getOrNull()
                ?: return null

            val imdbId = intent.getStringExtra(EXTRA_IMDB_ID)?.trim()?.ifBlank { null }
            val tmdbId = intent.getIntExtra(EXTRA_TMDB_ID, -1).takeIf { it > 0 }
            val season = intent.getIntExtra(EXTRA_SEASON, -1).takeIf { it > 0 }
            val episode = intent.getIntExtra(EXTRA_EPISODE, -1).takeIf { it > 0 }
            val year = intent.getIntExtra(EXTRA_YEAR, -1).takeIf { it > 0 }
            val showTitle = intent.getStringExtra(EXTRA_SHOW_TITLE)?.trim()?.ifBlank { null }
            val showYear = intent.getIntExtra(EXTRA_SHOW_YEAR, -1).takeIf { it > 0 }

            return PlaybackIdentity(
                imdbId = imdbId,
                tmdbId = tmdbId,
                contentType = contentType,
                season = season,
                episode = episode,
                title = title,
                year = year,
                showTitle = showTitle,
                showYear = showYear,
            )
        }
    }
}

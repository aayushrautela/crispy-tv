package com.crispy.tv.playerui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import coil.imageLoader
import coil.request.ImageRequest
import com.crispy.tv.R
import com.crispy.tv.nativeengine.playback.PlaybackSessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PlayerMediaSessionManager(
    context: Context,
    private val playbackController: PlaybackSessionController,
    restorePlaybackIntent: Intent,
) {
    private val appContext = context.applicationContext
    private val restorePlaybackIntent = Intent(restorePlaybackIntent)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val mediaSession =
        MediaSessionCompat(appContext, SESSION_TAG).apply {
            setCallback(
                object : MediaSessionCompat.Callback() {
                    override fun onPlay() {
                        playbackController.setPlaying(true)
                        currentIsPlaying = true
                        publishPlaybackState()
                        publishNotification(force = true)
                    }

                    override fun onPause() {
                        playbackController.setPlaying(false)
                        currentIsPlaying = false
                        currentIsBuffering = false
                        publishPlaybackState()
                        publishNotification(force = true)
                    }

                    override fun onSeekTo(pos: Long) {
                        playbackController.seekTo(pos)
                        currentPositionMs = pos.coerceAtLeast(0L)
                        publishPlaybackState()
                    }

                    override fun onRewind() {
                        val playbackSnapshot = playbackController.snapshot()
                        val targetPositionMs = (playbackSnapshot.positionMs - REWIND_MS).coerceAtLeast(0L)
                        playbackController.seekTo(targetPositionMs)
                        currentPositionMs = targetPositionMs
                        publishPlaybackState()
                    }

                    override fun onFastForward() {
                        val playbackSnapshot = playbackController.snapshot()
                        val durationMs = playbackSnapshot.durationMs
                        val unclampedTargetMs = playbackSnapshot.positionMs + FAST_FORWARD_MS
                        val targetPositionMs =
                            if (durationMs > 0L) {
                                unclampedTargetMs.coerceAtMost(durationMs)
                            } else {
                                unclampedTargetMs
                            }
                        playbackController.seekTo(targetPositionMs)
                        currentPositionMs = targetPositionMs
                        currentDurationMs = durationMs.coerceAtLeast(0L)
                        publishPlaybackState()
                    }

                    override fun onStop() {
                        playbackController.setPlaying(false)
                        currentIsPlaying = false
                        currentIsBuffering = false
                        publishPlaybackState()
                        publishNotification(force = true)
                    }
                },
            )
            setSessionActivity(buildContentPendingIntent())
            isActive = true
        }

    private var currentTitle: String = "Player"
    private var currentSubtitle: String? = null
    private var currentArtworkUrl: String? = null
    private var currentArtworkBitmap: Bitmap? = null
    private var currentIsPlaying: Boolean = false
    private var currentIsBuffering: Boolean = true
    private var currentPositionMs: Long = 0L
    private var currentDurationMs: Long = 0L
    private var artworkJob: Job? = null
    private var lastNotificationSnapshot: NotificationSnapshot? = null

    init {
        activeManager = this
        ensureNotificationChannel()
        publishPlaybackState()
        publishMetadata()
    }

    fun updateMetadata(
        title: String,
        subtitle: String?,
        artworkUrl: String?,
    ) {
        val normalizedTitle = title.ifBlank { "Player" }
        val normalizedSubtitle = subtitle?.trim()?.ifBlank { null }
        val normalizedArtworkUrl = artworkUrl?.trim()?.ifBlank { null }

        val artworkChanged = normalizedArtworkUrl != currentArtworkUrl
        currentTitle = normalizedTitle
        currentSubtitle = normalizedSubtitle
        currentArtworkUrl = normalizedArtworkUrl

        if (artworkChanged) {
            currentArtworkBitmap = null
            loadArtwork(normalizedArtworkUrl)
        }

        publishMetadata()
        publishNotification(force = artworkChanged)
    }

    fun updatePlayback(
        title: String,
        subtitle: String?,
        artworkUrl: String?,
        isPlaying: Boolean,
        isBuffering: Boolean,
        positionMs: Long,
        durationMs: Long,
    ) {
        updateMetadata(title = title, subtitle = subtitle, artworkUrl = artworkUrl)
        currentIsPlaying = isPlaying
        currentIsBuffering = isBuffering
        currentPositionMs = positionMs.coerceAtLeast(0L)
        currentDurationMs = durationMs.coerceAtLeast(0L)
        publishPlaybackState()
        publishNotification()
    }

    fun release() {
        artworkJob?.cancel()
        NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID)
        if (activeManager === this) {
            activeManager = null
        }
        mediaSession.isActive = false
        mediaSession.release()
        scope.cancel()
    }

    private fun loadArtwork(artworkUrl: String?) {
        artworkJob?.cancel()
        if (artworkUrl == null) {
            publishMetadata()
            publishNotification(force = true)
            return
        }

        artworkJob =
            scope.launch(Dispatchers.IO) {
                val bitmap =
                    runCatching {
                        val request =
                            ImageRequest.Builder(appContext)
                                .data(artworkUrl)
                                .allowHardware(false)
                                .size(960, 540)
                                .build()
                        val result = appContext.imageLoader.execute(request)
                        val drawable = result.drawable ?: return@runCatching null
                        when (drawable) {
                            is BitmapDrawable -> drawable.bitmap
                            else -> drawable.toBitmap()
                        }
                    }.getOrNull()

                withContext(Dispatchers.Main.immediate) {
                    if (currentArtworkUrl != artworkUrl) {
                        return@withContext
                    }
                    currentArtworkBitmap = bitmap
                    publishMetadata()
                    publishNotification(force = true)
                }
            }
    }

    private fun publishMetadata() {
        val metadata =
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, currentTitle)
                .apply {
                    currentSubtitle?.let {
                        putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, it)
                    }
                    if (currentDurationMs > 0L) {
                        putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentDurationMs)
                    }
                    currentArtworkBitmap?.let { bitmap ->
                        putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bitmap)
                        putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                        putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bitmap)
                    }
                }.build()
        mediaSession.setMetadata(metadata)
    }

    private fun publishPlaybackState() {
        val state =
            when {
                currentIsBuffering -> PlaybackStateCompat.STATE_BUFFERING
                currentIsPlaying -> PlaybackStateCompat.STATE_PLAYING
                else -> PlaybackStateCompat.STATE_PAUSED
            }

        val playbackState =
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_REWIND or
                        PlaybackStateCompat.ACTION_FAST_FORWARD or
                        PlaybackStateCompat.ACTION_STOP,
                )
                .setState(
                    state,
                    currentPositionMs,
                    if (currentIsPlaying) 1f else 0f,
                    SystemClock.elapsedRealtime(),
                ).build()

        mediaSession.setPlaybackState(playbackState)
    }

    private fun publishNotification(force: Boolean = false) {
        if (!canPostNotifications()) {
            return
        }

        val snapshot =
            NotificationSnapshot(
                title = currentTitle,
                subtitle = currentSubtitle,
                artworkUrl = currentArtworkUrl,
                hasArtwork = currentArtworkBitmap != null,
                isPlaying = currentIsPlaying,
                isBuffering = currentIsBuffering,
            )
        if (!force && snapshot == lastNotificationSnapshot) {
            return
        }

        NotificationManagerCompat.from(appContext).notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_player)
                .setContentTitle(currentTitle)
                .setContentText(currentSubtitle)
                .setContentIntent(buildContentPendingIntent())
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setOngoing(currentIsPlaying || currentIsBuffering)
                .setShowWhen(false)
                .setLargeIcon(currentArtworkBitmap)
                .addAction(
                    android.R.drawable.ic_media_rew,
                    "Replay 10 seconds",
                    buildActionPendingIntent(ACTION_REWIND),
                ).addAction(
                    if (currentIsPlaying || currentIsBuffering) {
                        android.R.drawable.ic_media_pause
                    } else {
                        android.R.drawable.ic_media_play
                    },
                    if (currentIsPlaying || currentIsBuffering) "Pause" else "Play",
                    buildActionPendingIntent(
                        if (currentIsPlaying || currentIsBuffering) ACTION_PAUSE else ACTION_PLAY,
                    ),
                ).addAction(
                    android.R.drawable.ic_media_ff,
                    "Forward 30 seconds",
                    buildActionPendingIntent(ACTION_FAST_FORWARD),
                ).setStyle(
                    MediaNotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession.sessionToken)
                        .setShowActionsInCompactView(0, 1, 2),
                ).build(),
        )

        lastNotificationSnapshot = snapshot
    }

    private fun buildContentPendingIntent(): PendingIntent {
        val intent = Intent(restorePlaybackIntent).setPackage(appContext.packageName)
        return PendingIntent.getActivity(
            appContext,
            REQUEST_CODE_CONTENT,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildActionPendingIntent(action: String): PendingIntent {
        val intent =
            Intent(appContext, PlayerNotificationActionReceiver::class.java)
                .setAction(action)
                .setPackage(appContext.packageName)
        return PendingIntent.getBroadcast(
            appContext,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canPostNotifications(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) {
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Playback",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Playback controls and PiP companion media notification"
            },
        )
    }

    private fun dispatchAction(action: String?) {
        when (action) {
            ACTION_PLAY -> mediaSession.controller.transportControls.play()
            ACTION_PAUSE -> mediaSession.controller.transportControls.pause()
            ACTION_REWIND -> mediaSession.controller.transportControls.rewind()
            ACTION_FAST_FORWARD -> mediaSession.controller.transportControls.fastForward()
            ACTION_STOP -> mediaSession.controller.transportControls.stop()
        }
    }

    private data class NotificationSnapshot(
        val title: String,
        val subtitle: String?,
        val artworkUrl: String?,
        val hasArtwork: Boolean,
        val isPlaying: Boolean,
        val isBuffering: Boolean,
    )

    companion object {
        private const val SESSION_TAG = "crispy-player-session"
        private const val NOTIFICATION_CHANNEL_ID = "crispy_player_playback"
        private const val NOTIFICATION_ID = 3001
        private const val REQUEST_CODE_CONTENT = 4001
        private const val ACTION_PLAY = "com.crispy.tv.playerui.action.PLAY"
        private const val ACTION_PAUSE = "com.crispy.tv.playerui.action.PAUSE"
        private const val ACTION_REWIND = "com.crispy.tv.playerui.action.REWIND"
        private const val ACTION_FAST_FORWARD = "com.crispy.tv.playerui.action.FAST_FORWARD"
        private const val ACTION_STOP = "com.crispy.tv.playerui.action.STOP"
        private const val REWIND_MS = 10_000L
        private const val FAST_FORWARD_MS = 30_000L

        @Volatile
        private var activeManager: PlayerMediaSessionManager? = null

        fun handleNotificationAction(action: String?) {
            activeManager?.dispatchAction(action)
        }
    }
}

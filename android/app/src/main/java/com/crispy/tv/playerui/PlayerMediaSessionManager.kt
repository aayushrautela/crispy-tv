package com.crispy.tv.playerui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
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

    private val player = PlaybackSessionControllerPlayer(appContext, playbackController)

    private val mediaSession: MediaSession =
        MediaSession.Builder(appContext, player)
            .setSessionActivity(buildContentPendingIntent())
            .build()

    private var currentTitle: String = "Player"
    private var currentSubtitle: String? = null
    private var currentArtworkUrl: String? = null
    private var currentArtworkBitmap: Bitmap? = null
    private var currentIsPlaying: Boolean = false
    private var currentIsBuffering: Boolean = true
    private var currentIsError: Boolean = false
    private var released: Boolean = false
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
        if (released) {
            return
        }
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
        playbackSpeed: Float = 1f,
        bufferedPositionMs: Long = positionMs,
    ) {
        if (released) {
            return
        }
        updateMetadata(title = title, subtitle = subtitle, artworkUrl = artworkUrl)
        val wasError = currentIsError
        currentIsPlaying = isPlaying
        currentIsBuffering = isBuffering
        currentIsError = false
        publishPlaybackState()
        publishNotification(force = wasError)
    }

    fun updatePlaybackError(
        title: String,
        subtitle: String?,
        artworkUrl: String?,
        positionMs: Long,
        durationMs: Long,
        errorMessage: String?,
    ) {
        if (released) {
            return
        }
        updateMetadata(title = title, subtitle = subtitle, artworkUrl = artworkUrl)
        currentIsError = true
        currentIsPlaying = false
        currentIsBuffering = false
        publishPlaybackState()
        publishNotification(force = true)
    }

    fun release() {
        released = true
        artworkJob?.cancel()
        NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID)
        if (activeManager === this) {
            activeManager = null
        }
        mediaSession.release()
        player.release()
        scope.cancel()
    }

    val isActive: Boolean
        get() = !released

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
                        val image = (result as? SuccessResult)?.image ?: return@runCatching null
                        image.toBitmap()
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
        player.updateMetadata(currentTitle, currentSubtitle, currentArtworkBitmap)
    }

    private fun publishPlaybackState() {
        player.invalidatePlaybackState()
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
                isError = currentIsError,
            )
        if (!force && snapshot == lastNotificationSnapshot) {
            return
        }

        val notification =
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
                .setStyle(
                    MediaStyleNotificationHelper.MediaStyle(mediaSession)
                        .setShowActionsInCompactView(0, 1, 2),
                ).build()

        NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)

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

    private data class NotificationSnapshot(
        val title: String,
        val subtitle: String?,
        val artworkUrl: String?,
        val hasArtwork: Boolean,
        val isPlaying: Boolean,
        val isBuffering: Boolean,
        val isError: Boolean,
    )

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "crispy_player_playback"
        private const val NOTIFICATION_ID = 3001
        private const val REQUEST_CODE_CONTENT = 4001

        @Volatile
        private var activeManager: PlayerMediaSessionManager? = null
    }
}

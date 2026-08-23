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
import android.util.Log
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
import java.io.ByteArrayOutputStream
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
    private var currentArtworkData: ByteArray? = null
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
        if (
            !artworkChanged &&
            normalizedTitle == currentTitle &&
            normalizedSubtitle == currentSubtitle
        ) {
            // Nothing changed: skip re-publishing so the per-tick poll loop stays cheap.
            return
        }
        currentTitle = normalizedTitle
        currentSubtitle = normalizedSubtitle
        currentArtworkUrl = normalizedArtworkUrl
        Log.d(
            TAG,
            "updateMetadata title=\"$normalizedTitle\" subtitle=$normalizedSubtitle artworkUrl=$normalizedArtworkUrl",
        )

        if (artworkChanged) {
            currentArtworkBitmap = null
            currentArtworkData = null
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
    ) {
        if (released) {
            return
        }
        updateMetadata(title = title, subtitle = subtitle, artworkUrl = artworkUrl)
        val wasError = currentIsError
        if (!wasError && currentIsPlaying == isPlaying && currentIsBuffering == isBuffering) {
            // Play/buffer state unchanged: the SimpleBasePlayer already mirrors live
            // position from the controller snapshot, so nothing needs republishing.
            return
        }
        Log.d(
            TAG,
            "updatePlayback isPlaying=$isPlaying isBuffering=$isBuffering" +
                " (was isPlaying=$currentIsPlaying isBuffering=$currentIsBuffering)",
        )
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
    ) {
        if (released) {
            return
        }
        updateMetadata(title = title, subtitle = subtitle, artworkUrl = artworkUrl)
        if (currentIsError) {
            return
        }
        currentIsError = true
        currentIsPlaying = false
        currentIsBuffering = false
        Log.d(TAG, "updatePlaybackError title=$currentTitle")
        publishPlaybackState()
        publishNotification(force = true)
    }

    fun release() {
        released = true
        Log.d(TAG, "release title=$currentTitle")
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
            Log.d(TAG, "artwork cleared")
            publishMetadata()
            publishNotification(force = true)
            return
        }
        Log.d(TAG, "artwork load start url=$artworkUrl")

        artworkJob =
            scope.launch(Dispatchers.IO) {
                val request =
                    ImageRequest.Builder(appContext)
                        .data(artworkUrl)
                        .allowHardware(false)
                        .size(ARTWORK_MAX_EDGE)
                        .build()
                val result = runCatching { appContext.imageLoader.execute(request) }.getOrNull()
                val bitmap = (result as? SuccessResult)?.image?.toBitmap()
                val data = bitmap?.let(::encodeSessionArtwork)

                withContext(Dispatchers.Main.immediate) {
                    if (currentArtworkUrl != artworkUrl) {
                        Log.d(TAG, "artwork discarded stale url=$artworkUrl")
                        return@withContext
                    }
                    if (bitmap == null) {
                        Log.w(TAG, "artwork load failed url=$artworkUrl result=$result")
                    } else {
                        Log.d(
                            TAG,
                            "artwork loaded ${bitmap.width}x${bitmap.height}" +
                                " bytes=${data?.size ?: 0}",
                        )
                    }
                    currentArtworkBitmap = bitmap
                    currentArtworkData = data
                    publishMetadata()
                    publishNotification(force = true)
                }
            }
    }

    private fun publishMetadata() {
        player.updateMetadata(currentTitle, currentSubtitle, currentArtworkData)
    }

    private fun publishPlaybackState() {
        player.invalidatePlaybackState()
    }

    private fun publishNotification(force: Boolean = false) {
        if (!canPostNotifications()) {
            Log.w(TAG, "notification suppressed: POST_NOTIFICATIONS not granted")
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
                .setSmallIcon(R.mipmap.ic_launcher_monochrome)
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
        Log.d(
            TAG,
            "notification posted title=\"$currentTitle\" art=${currentArtworkBitmap != null}" +
                " artworkBytes=${currentArtworkData?.size ?: 0} ongoing=${currentIsPlaying || currentIsBuffering}" +
                " isPlaying=$currentIsPlaying isBuffering=$currentIsBuffering force=$force",
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

    private fun encodeSessionArtwork(bitmap: Bitmap): ByteArray? {
        var candidate = bitmap
        var data = encodeJpeg(candidate)
        // The encoded payload crosses binder to SystemUI, so it must stay well below the
        // ~1MB transaction limit; shrink deterministically until the encode fits.
        while (data == null || data.size > MAX_ARTWORK_DATA_BYTES) {
            val nextWidth = (candidate.width * ARTWORK_SHRINK_FACTOR).toInt()
            val nextHeight = (candidate.height * ARTWORK_SHRINK_FACTOR).toInt()
            if (nextWidth < ARTWORK_MIN_EDGE || nextHeight < ARTWORK_MIN_EDGE) {
                break
            }
            candidate = Bitmap.createScaledBitmap(candidate, nextWidth, nextHeight, true)
            data = encodeJpeg(candidate)
        }
        return data
    }

    private fun encodeJpeg(bitmap: Bitmap): ByteArray? {
        val out = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, ARTWORK_JPEG_QUALITY, out)) {
            return null
        }
        return out.toByteArray()
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
        private const val TAG = "PlayerMediaSessionManager"
        private const val NOTIFICATION_CHANNEL_ID = "crispy_player_playback"
        private const val NOTIFICATION_ID = 3001
        private const val REQUEST_CODE_CONTENT = 4001
        private const val ARTWORK_MAX_EDGE = 1600
        private const val ARTWORK_JPEG_QUALITY = 90
        private const val MAX_ARTWORK_DATA_BYTES = 512 * 1024
        private const val ARTWORK_SHRINK_FACTOR = 0.75f
        private const val ARTWORK_MIN_EDGE = 320

        @Volatile
        private var activeManager: PlayerMediaSessionManager? = null
    }
}

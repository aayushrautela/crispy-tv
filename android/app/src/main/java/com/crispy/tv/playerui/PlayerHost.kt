package com.crispy.tv.playerui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.ContextCompat

/**
 * Capabilities the player destination needs from its host activity: picture-in-picture state and
 * configuration, and the notifications permission prompt for the playback notification.
 *
 * Provided at the composition root by [MainActivity]; the player destination reads it through
 * [LocalPlayerHost].
 */
interface PlayerHost {
    val isInPictureInPictureMode: State<Boolean>

    fun updatePictureInPictureConfig(config: PictureInPictureConfig)

    fun maybeRequestNotificationPermission()
}

val LocalPlayerHost = staticCompositionLocalOf<PlayerHost> {
    error("LocalPlayerHost not provided")
}

internal class ComponentActivityPlayerHost(
    private val activity: ComponentActivity,
) : PlayerHost {

    private val pipController = PlayerPipController(activity)

    override val isInPictureInPictureMode: State<Boolean>
        get() = pipController.isInPictureInPictureMode

    override fun updatePictureInPictureConfig(config: PictureInPictureConfig) {
        pipController.updateConfig(config)
    }

    override fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun onUserLeaveHint() {
        pipController.onUserLeaveHint()
    }

    fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        pipController.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    }

    private val permissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
}

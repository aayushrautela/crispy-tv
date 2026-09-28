package com.crispy.tv.playerui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.provider.Settings
import android.view.WindowManager
import kotlin.math.roundToInt

internal tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

internal class PlayerGestureController(
    private val activity: Activity,
    private val audioManager: AudioManager,
) {
    data class AudioLevel(
        val fraction: Float,
        val isMuted: Boolean,
    )

    private val originalBrightness = activity.window.attributes.screenBrightness
    private var brightnessRestored = false

    fun currentBrightness(): Float {
        val windowValue = activity.window.attributes.screenBrightness
        return if (windowValue in 0f..1f) {
            windowValue.coerceIn(0.02f, 1f)
        } else {
            readSystemBrightness()
        }
    }

    fun setBrightness(level: Float): Float {
        val target = level.coerceIn(0.02f, 1f)
        val attributes = activity.window.attributes
        attributes.screenBrightness = target
        activity.window.attributes = attributes
        return target
    }

    fun currentVolume(): AudioLevel {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, maxVolume)
        return AudioLevel(
            fraction = currentVolume.toFloat() / maxVolume.toFloat(),
            isMuted = currentVolume == 0,
        )
    }

    fun setVolume(level: Float): AudioLevel {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val targetVolume = (level.coerceIn(0f, 1f) * maxVolume.toFloat())
            .roundToInt()
            .coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
        return AudioLevel(
            fraction = targetVolume.toFloat() / maxVolume.toFloat(),
            isMuted = targetVolume == 0,
        )
    }

    fun restoreBrightness() {
        if (brightnessRestored) return
        brightnessRestored = true
        val attributes = activity.window.attributes
        attributes.screenBrightness = when {
            originalBrightness < 0f -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            else -> originalBrightness.coerceIn(0f, 1f)
        }
        activity.window.attributes = attributes
    }

    private fun readSystemBrightness(): Float =
        runCatching {
            Settings.System.getInt(
                activity.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
            )
        }.getOrDefault(127)
            .coerceIn(1, 255)
            .toFloat() / 255f
}

internal fun tryCreateGestureController(activity: Activity?): PlayerGestureController? {
    if (activity == null) return null
    val audioManager = activity.getSystemService(Activity.AUDIO_SERVICE) as? AudioManager ?: return null
    return PlayerGestureController(activity, audioManager)
}

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

/**
 * The only [PlayerGestureController] there is.
 *
 * It is `androidMain` because every member of it reaches a framework API:
 * `Activity.window.attributes` for brightness, `AudioManager` for the volume
 * fraction, and `Settings.System` for the screen brightness a window does not
 * override. The interface it implements is in `commonMain` so the drag-gesture
 * modifier can be written once.
 *
 * The nested `AudioLevel` this used to declare is gone -- it is a top-level type in
 * the interface's file, because `PlayerGestures.kt` names it as a parameter type.
 * The name is deliberately unchanged so no consumer needed an import edit.
 */
internal class AndroidPlayerGestureController(
    private val activity: Activity,
    private val audioManager: AudioManager,
) : PlayerGestureController {
    private val originalBrightness = activity.window.attributes.screenBrightness
    private var brightnessRestored = false

    override fun currentBrightness(): Float {
        val windowValue = activity.window.attributes.screenBrightness
        return if (windowValue in 0f..1f) {
            windowValue.coerceIn(0.02f, 1f)
        } else {
            readSystemBrightness()
        }
    }

    override fun setBrightness(level: Float): Float {
        val target = level.coerceIn(0.02f, 1f)
        val attributes = activity.window.attributes
        attributes.screenBrightness = target
        activity.window.attributes = attributes
        return target
    }

    override fun currentVolume(): AudioLevel {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, maxVolume)
        return AudioLevel(
            fraction = currentVolume.toFloat() / maxVolume.toFloat(),
            isMuted = currentVolume == 0,
        )
    }

    override fun setVolume(level: Float): AudioLevel {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        // The `coerceIn(0f, 1f)` here is the FIFTEENTH redundant guard in this
        // repository, and the mutation that proved it is `set-volume-is-not-clamped-below-zero`.
        // Removing it compiles, and no test changes, because the `coerceIn(0, maxVolume)` two
        // lines below already clamps the rounded *Int* into the same range: -4f gives
        // (-4f * 15).roundToInt() = -60, clamped to 0; 1.4f gives 21, clamped to 15; and a
        // value like -0.01f rounds to 0 before the clamp ever sees it. So the Float clamp can
        // never be the thing that decides the answer.
        //
        // It is kept rather than deleted, and the reason is not a test: it states the
        // contract at the point where the fraction enters. The downstream Int clamp is a
        // consequence of rounding to a step count, and it is the one that would be removed by
        // someone tidying the arithmetic; with the Float clamp gone, `setVolume(-4f)` would
        // then be `-4f * max` and the *returned* `fraction` would be computed from an
        // unclamped step. It is the statement of intent, and it becomes load-bearing the day
        // the rounding changes. Written down here so the surviving mutation is not read as a
        // gap in the suite -- `setVolumeClampsTheRequestedFractionAtBothEnds` does pin the
        // behaviour, it simply cannot see which of the two clamps produced it.
        val targetVolume = (level.coerceIn(0f, 1f) * maxVolume.toFloat())
            .roundToInt()
            .coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
        return AudioLevel(
            fraction = targetVolume.toFloat() / maxVolume.toFloat(),
            isMuted = targetVolume == 0,
        )
    }

    override fun restoreBrightness() {
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
    return AndroidPlayerGestureController(activity, audioManager)
}

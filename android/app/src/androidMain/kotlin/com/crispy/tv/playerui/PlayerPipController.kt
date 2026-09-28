package com.crispy.tv.playerui

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlin.math.roundToInt

/**
 * Owns picture-in-picture state and parameters for the host [ComponentActivity].
 *
 * The player UI drives configuration through [updateConfig] as video geometry settles; the
 * activity forwards [onUserLeaveHint] and [onPictureInPictureModeChanged] so auto-enter works
 * and composables can observe the current mode.
 */
internal class PlayerPipController(private val activity: ComponentActivity) {

    val isInPictureInPictureMode: MutableState<Boolean> =
        mutableStateOf(activity.isInPictureInPictureMode)

    private var enabled: Boolean = false
    private var sourceRect: Rect? = null
    private var aspectRatio: Rational? = null
    private var lastAppliedConfig: PictureInPictureConfig? = null

    fun updateConfig(config: PictureInPictureConfig) {
        // setPictureInPictureParams is a window transaction; skip redundant pushes that
        // recomposition can fire with an unchanged config.
        if (config == lastAppliedConfig) {
            return
        }
        enabled = config.enabled
        sourceRect = config.sourceRect
        aspectRatio = config.aspectRatio
        lastAppliedConfig = config

        Log.d(TAG, "updateConfig enabled=${config.enabled} aspectRatio=${config.aspectRatio}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.setPictureInPictureParams(buildParams())
        }
    }

    fun onUserLeaveHint() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        if (!enabled || isInPictureInPictureMode.value || activity.isFinishing || activity.isDestroyed) {
            return
        }
        runCatching {
            activity.enterPictureInPictureMode(buildParams())
        }.onFailure { error ->
            Log.w(TAG, "enterPictureInPictureMode failed", error)
        }
    }

    fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        this.isInPictureInPictureMode.value = isInPictureInPictureMode
        Log.d(TAG, "onPictureInPictureModeChanged isInPip=$isInPictureInPictureMode")
    }

    private fun buildParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()

        sourceRect?.let(builder::setSourceRectHint)
        aspectRatio
            ?.takeIf { it.numerator > 0 && it.denominator > 0 }
            ?.let(::clampAspectRatio)
            ?.let(builder::setAspectRatio)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(enabled)
            builder.setSeamlessResizeEnabled(true)
        }

        return builder.build()
    }

    private fun clampAspectRatio(rational: Rational): Rational {
        val ratio = rational.toDouble()
        return when {
            ratio > MAX_ASPECT_RATIO -> Rational((MAX_ASPECT_RATIO * 100).roundToInt(), 100)
            ratio < MIN_ASPECT_RATIO -> Rational(100, (MAX_ASPECT_RATIO * 100).roundToInt())
            else -> rational
        }
    }

    private companion object {
        const val TAG = "PlayerPipController"
        const val MAX_ASPECT_RATIO = 2.39
        const val MIN_ASPECT_RATIO = 1.0 / MAX_ASPECT_RATIO
    }
}

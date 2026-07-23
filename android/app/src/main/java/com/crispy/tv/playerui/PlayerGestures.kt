package com.crispy.tv.playerui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.roundToInt

internal const val LEFT_GESTURE_BOUNDARY = 0.4f
internal const val RIGHT_GESTURE_BOUNDARY = 0.6f
internal const val VERTICAL_GESTURE_SENSITIVITY = 0.65f
internal const val VERTICAL_GESTURE_SLOP_MULTIPLIER = 3f
internal const val VERTICAL_GESTURE_MIN_HEIGHT_FRACTION = 0.06f
internal const val VERTICAL_GESTURE_DOMINANCE_RATIO = 1.2f
internal const val DOUBLE_TAP_SEEK_STEP_MS = 10_000L

internal fun Modifier.playerVerticalDragGestures(
    gestureController: PlayerGestureController?,
    onBrightnessChange: (Float) -> Unit,
    onVolumeChange: (PlayerGestureController.AudioLevel) -> Unit,
): Modifier = this.then(
    if (gestureController == null) {
        Modifier
    } else {
        Modifier.pointerInput(gestureController) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnchangedPosition = false)
                val width = size.width.toFloat().takeIf { it > 0f } ?: return@awaitEachGesture
                val height = size.height.toFloat().takeIf { it > 0f } ?: return@awaitEachGesture

                val region = when {
                    down.position.x < width * LEFT_GESTURE_BOUNDARY -> GestureRegion.BRIGHTNESS
                    down.position.x > width * RIGHT_GESTURE_BOUNDARY -> GestureRegion.VOLUME
                    else -> return@awaitEachGesture
                }

                val initialBrightness = if (region == GestureRegion.BRIGHTNESS) gestureController.currentBrightness() else null
                val initialVolume = if (region == GestureRegion.VOLUME) gestureController.currentVolume() else null

                var totalDy = 0f
                var gestureMode: VerticalGestureMode? = null
                var verticalActivationDy = 0f

                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break

                    val delta = change.position - change.previousPosition
                    totalDy += delta.y

                    if (gestureMode == null) {
                        val activationSlop = maxOf(
                            viewConfiguration.touchSlop * VERTICAL_GESTURE_SLOP_MULTIPLIER,
                            height * VERTICAL_GESTURE_MIN_HEIGHT_FRACTION,
                        )
                        val verticalDominant =
                            abs(totalDy) > activationSlop &&
                                abs(totalDy) > abs(delta.x) * VERTICAL_GESTURE_DOMINANCE_RATIO

                        gestureMode = when {
                            verticalDominant && region == GestureRegion.BRIGHTNESS && initialBrightness != null -> {
                                verticalActivationDy = totalDy
                                VerticalGestureMode.BRIGHTNESS
                            }
                            verticalDominant && region == GestureRegion.VOLUME && initialVolume != null -> {
                                verticalActivationDy = totalDy
                                VerticalGestureMode.VOLUME
                            }
                            else -> null
                        }
                        if (gestureMode == null) continue
                    }

                    when (gestureMode) {
                        VerticalGestureMode.BRIGHTNESS -> {
                            val activeDy = totalDy - verticalActivationDy
                            val deltaFraction = (-activeDy / height) * VERTICAL_GESTURE_SENSITIVITY
                            gestureController.setBrightness(initialBrightness + deltaFraction)
                                .let(onBrightnessChange)
                        }
                        VerticalGestureMode.VOLUME -> {
                            val activeDy = totalDy - verticalActivationDy
                            val deltaFraction = (-activeDy / height) * VERTICAL_GESTURE_SENSITIVITY
                            gestureController.setVolume(initialVolume.fraction + deltaFraction)
                                .let(onVolumeChange)
                        }
                    }
                    change.consume()
                }
            }
        }
    },
)

internal fun formatGestureBrightness(level: Float): String =
    "${(level.coerceIn(0f, 1f) * 100f).roundToInt()}%"

internal fun formatGestureVolume(level: PlayerGestureController.AudioLevel): String =
    if (level.isMuted) "Muted" else "${(level.fraction.coerceIn(0f, 1f) * 100f).roundToInt()}%"

internal fun playbackSeekDeltaLabel(targetMs: Long, currentMs: Long): String {
    val delta = targetMs - currentMs
    val seconds = abs(delta) / 1000L
    return if (delta >= 0) "+${seconds}s" else "-${seconds}s"
}

private enum class GestureRegion {
    BRIGHTNESS,
    VOLUME,
}

private enum class VerticalGestureMode {
    BRIGHTNESS,
    VOLUME,
}


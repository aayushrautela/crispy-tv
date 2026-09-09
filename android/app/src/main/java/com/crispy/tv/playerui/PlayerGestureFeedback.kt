package com.crispy.tv.playerui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.crispy.tv.domain.player.TapZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun rememberGestureFeedback(): GestureFeedbackState {
    return remember { GestureFeedbackState() }
}

internal class GestureFeedbackState {
    val message: MutableState<GestureFeedbackMessage?> = mutableStateOf(null)
    var hideJob: Job? = null

    fun show(scope: CoroutineScope, value: GestureFeedbackMessage, holdMs: Long) {
        hideJob?.cancel()
        message.value = value
        hideJob = scope.launch {
            delay(holdMs)
            message.value = null
        }
    }

    fun clear() {
        hideJob?.cancel()
        message.value = null
    }
}

@Composable
internal fun GestureFeedbackOverlay(
    feedback: GestureFeedbackState,
    modifier: Modifier = Modifier,
) {
    val message = feedback.message.value
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        if (message != null) {
            Box(modifier = Modifier.fillMaxSize()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 40.dp, start = 16.dp, end = 16.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.68f),
                    contentColor = Color.White,
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = message.icon,
                                contentDescription = null,
                                tint = Color.White,
                            )
                            Text(
                                text = message.text,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                            )
                        }
                    }
                }
            }
        }
    }
}

internal data class GestureFeedbackMessage(
    val text: String,
    val icon: ImageVector,
)

internal data class SeekRippleState(
    val side: TapZone,
    val totalDeltaMs: Long,
    val tapCount: Int,
) {
    val isForward: Boolean get() = totalDeltaMs >= 0
}

@Composable
internal fun SeekRippleOverlay(
    state: SeekRippleState?,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        val ripple = state ?: return@AnimatedVisibility
        val density = LocalDensity.current
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            val zoneInset = maxWidth * 0.10f
            val chevronStepPx = with(density) { 32.dp.toPx() }
            val slide = remember { Animatable(0f) }
            val pulse = remember { Animatable(1f) }
            LaunchedEffect(ripple.tapCount) {
                slide.snapTo(if (ripple.isForward) -chevronStepPx else chevronStepPx)
                slide.animateTo(0f, tween(durationMillis = 250, easing = FastOutSlowInEasing))
                pulse.snapTo(0.6f)
                pulse.animateTo(1f, tween(durationMillis = 200))
            }
            Column(
                modifier =
                    Modifier
                        .align(if (ripple.side == TapZone.LEFT) Alignment.CenterStart else Alignment.CenterEnd)
                        .padding(horizontal = zoneInset)
                        .graphicsLayer { alpha = pulse.value },
                horizontalAlignment =
                    if (ripple.side == TapZone.LEFT) Alignment.Start else Alignment.End,
            ) {
                Row(
                    modifier = Modifier.graphicsLayer { translationX = slide.value },
                ) {
                    repeat(2) {
                        Icon(
                            imageVector =
                                if (ripple.isForward) {
                                    Icons.Filled.ChevronRight
                                } else {
                                    Icons.Filled.ChevronLeft
                                },
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
                Text(
                    text = playbackSeekDeltaLabel(ripple.totalDeltaMs),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
            }
        }
    }
}

internal object GestureIcons {
    val Brightness = Icons.Filled.Brightness6
    val VolumeUp = Icons.AutoMirrored.Filled.VolumeUp
    val VolumeMuted = Icons.AutoMirrored.Filled.VolumeOff
    val Resize = Icons.Filled.Crop
}


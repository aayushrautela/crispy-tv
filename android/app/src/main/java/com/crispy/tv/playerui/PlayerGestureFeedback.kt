package com.crispy.tv.playerui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
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
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(
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

internal object GestureIcons {
    val Brightness = Icons.Filled.Brightness6
    val VolumeUp = Icons.Filled.VolumeUp
    val VolumeMuted = Icons.Filled.VolumeOff
    val Forward10 = Icons.Filled.Forward10
    val Backward10 = Icons.Filled.Replay10
}


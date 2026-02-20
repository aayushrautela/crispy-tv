package com.crispy.rewrite.introskip

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private const val AUTO_HIDE_DELAY_MS = 15_000L

@Composable
fun IntroSkipButtonOverlay(
    enabled: Boolean,
    currentPositionMs: Long,
    intervals: List<IntroSkipInterval>,
    onSkipRequested: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!enabled) {
        return
    }

    val activeInterval = remember(intervals, currentPositionMs) {
        intervals.firstOrNull { it.isActiveAt(currentPositionMs) }
    }

    var activeKey by remember { mutableStateOf<String?>(null) }
    var hasSkippedCurrent by remember { mutableStateOf(false) }
    var autoHidden by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(activeInterval?.stableKey) {
        val key = activeInterval?.stableKey
        if (key != activeKey) {
            activeKey = key
            hasSkippedCurrent = false
            autoHidden = false
        }
    }

    LaunchedEffect(activeInterval?.stableKey, hasSkippedCurrent, autoHidden) {
        if (activeInterval == null || hasSkippedCurrent || autoHidden) {
            visible = false
            return@LaunchedEffect
        }

        val currentKey = activeInterval.stableKey
        visible = true
        delay(AUTO_HIDE_DELAY_MS)
        if (activeKey == currentKey && !hasSkippedCurrent) {
            autoHidden = true
            visible = false
        }
    }

    val interval = activeInterval ?: return
    if (hasSkippedCurrent || autoHidden) {
        return
    }

    AnimatedVisibility(
        modifier = modifier,
        visible = visible,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut()
    ) {
        FilledTonalButton(
            modifier = Modifier.testTag("intro_skip_button"),
            onClick = {
                hasSkippedCurrent = true
                visible = false
                onSkipRequested(interval.endTimeMs)
            }
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.SkipNext,
                    contentDescription = null
                )
                Text(skipLabelFor(interval.segmentType))
            }
        }
    }
}

private fun skipLabelFor(segmentType: IntroSkipSegmentType): String {
    return when (segmentType) {
        IntroSkipSegmentType.INTRO,
        IntroSkipSegmentType.OP,
        IntroSkipSegmentType.MIXED_OP -> "Skip Intro"

        IntroSkipSegmentType.OUTRO,
        IntroSkipSegmentType.ED,
        IntroSkipSegmentType.MIXED_ED -> "Skip Ending"

        IntroSkipSegmentType.RECAP -> "Skip Recap"
        IntroSkipSegmentType.UNKNOWN -> "Skip"
    }
}

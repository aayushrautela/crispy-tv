package com.crispy.tv.ui.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

const val SharedElementDurationMillis = 300

val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

val LocalNavAnimatedContentScope = staticCompositionLocalOf<AnimatedContentScope?> { null }

@Composable
fun AnimatedContentScope.animateContentAlpha(): Float {
    val alpha by transition.animateFloat(
        transitionSpec = { tween(SharedElementDurationMillis) },
        label = "sharedContentAlpha",
    ) { state ->
        when (state) {
            EnterExitState.Visible -> 1f
            else -> 0f
        }
    }
    return alpha
}

@Composable
fun AnimatedContentScope.animateCardCornerRadius(cardRadius: Dp): Dp {
    val corner by transition.animateDp(
        transitionSpec = { tween(SharedElementDurationMillis) },
        label = "sharedCardCornerRadius",
    ) { state ->
        when (state) {
            EnterExitState.Visible -> cardRadius
            else -> 0.dp
        }
    }
    return corner
}

@Composable
fun AnimatedContentScope.animateHeroCornerRadius(cardRadius: Dp): Dp {
    val corner by transition.animateDp(
        transitionSpec = { tween(SharedElementDurationMillis) },
        label = "sharedHeroCornerRadius",
    ) { state ->
        when (state) {
            EnterExitState.Visible -> 0.dp
            else -> cardRadius
        }
    }
    return corner
}

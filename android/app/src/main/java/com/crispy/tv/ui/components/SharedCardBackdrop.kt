package com.crispy.tv.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.crispy.tv.ui.navigation.LocalNavAnimatedContentScope
import com.crispy.tv.ui.navigation.LocalSharedTransitionScope
import com.crispy.tv.ui.navigation.animateCardCornerRadius
import com.crispy.tv.ui.navigation.animateCardOverlayAlpha

@Composable
fun sharedCardBackdropModifier(
    sharedElementKey: String?,
    cornerRadius: Dp,
    fillMaxSize: Boolean = true,
    animateOverlayFade: Boolean = true,
): Modifier {
    val sizeModifier = if (fillMaxSize) Modifier.fillMaxSize() else Modifier
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalNavAnimatedContentScope.current
    if (sharedTransitionScope == null || animatedVisibilityScope == null || sharedElementKey.isNullOrBlank()) {
        return sizeModifier
    }
    val screenBackground = MaterialTheme.colorScheme.background
    val bottomFadeBrush = remember(screenBackground) {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.66f to Color.Transparent,
                1f to screenBackground,
            ),
        )
    }
    val animatedCornerRadius = with(animatedVisibilityScope) {
        animateCardCornerRadius(cornerRadius)
    }
    val overlayAlpha = if (animateOverlayFade) {
        with(animatedVisibilityScope) { animateCardOverlayAlpha() }
    } else {
        0f
    }
    val fadeModifier = if (animateOverlayFade) {
        Modifier.drawWithContent {
            drawContent()
            if (overlayAlpha > 0.001f) {
                drawRect(brush = bottomFadeBrush, alpha = overlayAlpha)
            }
        }
    } else {
        Modifier
    }
    return with(sharedTransitionScope) {
        Modifier
            .sharedElement(
                rememberSharedContentState(key = sharedElementKey),
                animatedVisibilityScope = animatedVisibilityScope,
            )
            .clip(RoundedCornerShape(animatedCornerRadius))
            .then(sizeModifier)
            .then(fadeModifier)
    }
}

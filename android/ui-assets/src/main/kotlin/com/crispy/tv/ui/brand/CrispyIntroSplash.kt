package com.crispy.tv.ui.brand

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composableimport androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.crispy.tv.ui.assets.R

private const val IntroDurationMs = 1200
private const val RevealVisibilityThreshold = 0.01f

private val IntroBackground: Color = Color(0xFF141414)

private val EnterEasing: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)
private val EmphasizedDecelerateEasing: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val WipeEasing: Easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private val SettleEasing: Easing = CubicBezierEasing(0.3f, 0f, 0.2f, 1f)

private fun segment(progress: Float, start: Float, end: Float): Float {
    return ((progress - start) / (end - start)).coerceIn(0f, 1f)
}

private fun lerp(start: Float, end: Float, fraction: Float): Float {
    return start + (end - start) * fraction
}

/**
 * App-start intro: the mark pops in centered, then the wordmark wipes in
 * left-to-right (crispy, then tv) while pushing the mark left into the
 * final lockup, ending with a subtle overshoot settle.
 *
 * A single linear master progress drives every channel so the whole
 * choreography stays in sync. When [playIntro] is false everything snaps
 * to the finished lockup (used after the intro has played once).
 */
@Composable
fun CrispyIntroSplash(
    modifier: Modifier = Modifier,
    brandHeight: Dp = 56.dp,
    playIntro: Boolean = true,
    onFinished: () -> Unit = {},
) {
    // Unit-matched to the original lockup: text slices use a 72-unit-tall
    // frame, so the mark box uses the same units-per-dp (57x69 art).
    val markWidth: Dp = brandHeight * (57f / 72f)
    val markBoxHeight: Dp = brandHeight * (69f / 72f)
    val crispyWidth: Dp = brandHeight * (176f / 72f)
    val tvWidth: Dp = brandHeight * (53f / 72f)
    // Gaps measured art-edge to art-edge in the original outlines.
    val markGap: Dp = brandHeight * (18.9f / 72f)
    val wordGap: Dp = brandHeight * (17.3f / 72f)

    val progress = remember { Animatable(0f) }

    LaunchedEffect(playIntro) {
        if (!playIntro) {
            progress.snapTo(1f)
            onFinished()
            return@LaunchedEffect
        }
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = IntroDurationMs,
                easing = LinearEasing,
            ),
        )
        onFinished()
    }

    val p = progress.value

    val markAlpha = EnterEasing.transform(segment(p, 0f, 0.25f))
    val markPop = segment(p, 0f, 0.22f)
    val markRecover = segment(p, 0.22f, 0.42f)
    val markScale = lerp(
        lerp(0.92f, 1.03f, EnterEasing.transform(markPop)),
        1f,
        SettleEasing.transform(markRecover),
    )

    val pushRaw = segment(p, 0.29f, 0.88f)
    val pushArrive = EmphasizedDecelerateEasing.transform((pushRaw / 0.9f).coerceIn(0f, 1f))
    val pushSettle = SettleEasing.transform(((pushRaw - 0.9f) / 0.1f).coerceIn(0f, 1f))
    val push = lerp(lerp(0f, 1.02f, pushArrive), 1f, pushSettle)

    // Morph from the system splash: open with the mark at splash size
    // (168dp art), then shrink into the final lockup as the wordmark wipes.
    val openScale = 168f / markBoxHeight.value
    val rowScale = lerp(openScale, 1f, EmphasizedDecelerateEasing.transform(pushRaw))

    val wipe = WipeEasing.transform(segment(p, 0.33f, 0.87f))
    val wipeTv = WipeEasing.transform(segment(p, 0.42f, 0.92f))
    val crispyAlpha = (wipe * 1.6f).coerceIn(0f, 1f)
    val tvAlpha = (wipeTv * 1.6f).coerceIn(0f, 1f)

    val driftPx = with(LocalDensity.current) { 10.dp.toPx() } * (1f - push.coerceIn(0f, 1f))

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(IntroBackground),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.graphicsLayer {
                scaleX = rowScale
                scaleY = rowScale
            },
        ) {
            Image(
                painter = painterResource(R.drawable.brand_mark),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .width(markWidth)
                    .height(markBoxHeight)
                    .graphicsLayer {
                        alpha = markAlpha
                        scaleX = markScale
                        scaleY = markScale
                    },
            )
            if (push > RevealVisibilityThreshold) {
                Spacer(modifier = Modifier.width(markGap * push))
                if (wipe > RevealVisibilityThreshold) {
                    Box(
                        contentAlignment = Alignment.CenterStart,
                        modifier = Modifier
                            .width(crispyWidth * wipe)
                            .height(brandHeight)
                            .clipToBounds()
                            .graphicsLayer {
                                alpha = crispyAlpha
                                translationX = driftPx
                            },
                    ) {
                        Image(
                            painter = painterResource(R.drawable.brand_wordmark_crispy),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .width(crispyWidth)
                                .height(brandHeight),
                        )
                    }
                }
                if (wipeTv > RevealVisibilityThreshold) {
                    Spacer(modifier = Modifier.width(wordGap * push))
                    Box(
                        contentAlignment = Alignment.CenterStart,
                        modifier = Modifier
                            .width(tvWidth * wipeTv)
                            .height(brandHeight)
                            .clipToBounds()
                            .graphicsLayer {
                                alpha = tvAlpha
                                translationX = driftPx
                            },
                    ) {
                        Image(
                            painter = painterResource(R.drawable.brand_wordmark_tv),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .width(tvWidth)
                                .height(brandHeight),
                        )
                    }
                }
            }
        }
    }
}

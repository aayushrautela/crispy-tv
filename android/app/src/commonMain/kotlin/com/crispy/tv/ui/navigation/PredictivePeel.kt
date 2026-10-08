package com.crispy.tv.ui.navigation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner

/**
 * Corner radius of the peeling page at full gesture progress, in dp.
 *
 * Measured from the reference implementation this work learns from, not chosen:
 * the foreground card rounds to 28.dp as the drag reaches 1.0.
 */
internal const val PredictivePeelMaxCornerRadiusDp = 28f

/**
 * Settle duration for the gesture's release tail, in milliseconds.
 *
 * This is the library's own number, not ours: `NavHost`'s commit/cancel path
 * animates the scrubbed fraction with a default `tween()` (verified in the
 * resolved `navigation-compose` classes -- `tween$default` with every argument
 * defaulted, i.e. 300ms). The corner tail below uses the same constant so the
 * shape and the motion finish together. If a future navigation version changes
 * its default, this is the line that must follow it.
 */
internal const val PredictiveSettleDurationMillis = 300

/**
 * Which way a back gesture moves, as a sign on offsets.
 *
 * The edge is physical -- left edge means the finger travels right -- so there
 * is deliberately no RTL adjustment here. `EDGE_NONE` (a back invoked without
 * edge information) falls back to +1, the left-edge direction, rather than 0:
 * a 0 sign would freeze the motion while the gesture still runs.
 *
 * `internal` for the same reason as [roleOf]: the rule is already a named
 * top-level function, so only the visibility stood between it and `commonTest`.
 */
internal fun peelSign(swipeEdge: Int): Int =
    if (swipeEdge == NavigationEvent.EDGE_RIGHT) -1 else 1

/**
 * The shared gesture reading both halves of the peel observe.
 *
 * `progress` is the live scrub fraction while a system back gesture runs and
 * 0 at rest; `active` is true exactly while the state is
 * [NavigationEventTransitionState.InProgress]. Read from
 * `NavigationEventDispatcher.transitionState`, which is the dispatcher's
 * public read-only flow over the whole tree's shared processor -- observing it
 * registers no handler and takes the gesture from nobody, so `NavHost` keeps
 * its scrub, its reveal, and its shared-element transitions.
 */
internal data class PredictivePeel(
    val progress: Float,
    val active: Boolean,
)

/** `internal` for the same reason as [peelSign]. */
@Composable
internal fun rememberPredictivePeel(): PredictivePeel {
    // Nullable: nothing provides an owner outside a navigation host (previews,
    // tests). No owner means no gesture, which is the rest pose, not an error.
    val owner = LocalNavigationEventDispatcherOwner.current
        ?: return PredictivePeel(progress = 0f, active = false)
    val transitionState by owner.navigationEventDispatcher.transitionState.collectAsState()
    val inProgress = transitionState as? NavigationEventTransitionState.InProgress
    return PredictivePeel(
        progress = inProgress?.latestEvent?.progress ?: 0f,
        active = inProgress != null,
    )
}

/**
 * Rounds the peeling page's corners with the gesture. Destination-layer only.
 *
 * A transition cannot express a shape -- `EnterTransition`/`ExitTransition`
 * are fade/slide/scale only -- so the corner lives here while all motion
 * stays in `AppNavHost`'s `predictivePop*`. Both read the same physical
 * gesture, so they cannot disagree.
 *
 * While the finger is down the exact fraction is used. On release the observer
 * goes `Idle` at once while `NavHost` still tweens its ~300ms settle, so a
 * warm chaser carries the corner home over the same duration instead of
 * snapping it square one frame early. Off at rest: below the epsilon the
 * caller's own modifier is returned untouched, so no render layer is
 * allocated for a 0.dp corner.
 */
@Composable
internal fun Modifier.predictivePeelClip(): Modifier {
    val peel = rememberPredictivePeel()
    val settled by animateFloatAsState(
        targetValue = if (peel.active) peel.progress else 0f,
        animationSpec = tween(PredictiveSettleDurationMillis),
        label = "predictivePeelCorner",
    )
    val progress = if (peel.active) peel.progress else settled
    if (progress <= 0.001f) return this
    return graphicsLayer {
        shape = RoundedCornerShape((PredictivePeelMaxCornerRadiusDp * progress).dp)
        clip = true
    }
}

/**
 * The destination wrapper every `composable()` registration applies around its
 * content.
 *
 * A full-screen layer carrying [predictivePeelClip], so the corner always spans
 * the screen regardless of what the screen's own root does. The wrapper owns
 * no motion -- that stays in `AppNavHost`'s `predictivePop*` -- and at rest it
 * is a transparent pass-through: [predictivePeelClip] returns the incoming
 * modifier untouched below its epsilon, so no render layer is allocated for a
 * 0.dp corner.
 */
@Composable
internal fun PredictivePeelContainer(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().predictivePeelClip(),
        content = { content() },
    )
}

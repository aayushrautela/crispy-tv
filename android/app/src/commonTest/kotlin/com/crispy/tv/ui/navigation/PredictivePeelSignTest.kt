package com.crispy.tv.ui.navigation

import androidx.navigationevent.NavigationEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The gesture-direction rule the two predictive `NavHost` transitions are
 * written from.
 *
 * `peelSign` is the one decision in the predictive layer a `commonTest` can
 * reach: the transitions themselves return `EnterTransition`/`ExitTransition`
 * values and stay `private` (asserting their specs would prove Compose's
 * builders work, not that the app peels the right way -- the same split
 * `AppNavHostNavigationRoleTest` draws). The edge constants are asserted by
 * value because the sign rule is written against them: if a future
 * `navigationevent` version renumbered the edges, `peelSign` would silently
 * answer the wrong sign while still compiling.
 */
class PredictivePeelSignTest {

    @Test
    fun leftEdgeMovesPositive() {
        assertEquals(1, peelSign(NavigationEvent.EDGE_LEFT), "swipeEdge of EDGE_LEFT")
    }

    @Test
    fun rightEdgeMovesNegative() {
        assertEquals(-1, peelSign(NavigationEvent.EDGE_RIGHT), "swipeEdge of EDGE_RIGHT")
    }

    /**
     * `EDGE_NONE` is a back invoked without edge information. It falls back to
     * the left-edge direction rather than 0: a 0 sign would freeze the motion
     * while the gesture still runs.
     */
    @Test
    fun noEdgeFallsBackToPositiveRatherThanZero() {
        assertEquals(1, peelSign(NavigationEvent.EDGE_NONE), "swipeEdge of EDGE_NONE")
    }

    @Test
    fun edgeConstantsAreTheVerifiedNumbering() {
        assertEquals(0, NavigationEvent.EDGE_LEFT, "EDGE_LEFT")
        assertEquals(1, NavigationEvent.EDGE_RIGHT, "EDGE_RIGHT")
        assertEquals(2, NavigationEvent.EDGE_NONE, "EDGE_NONE")
    }
}

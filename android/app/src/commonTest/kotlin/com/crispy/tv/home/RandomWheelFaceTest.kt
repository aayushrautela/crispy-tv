package com.crispy.tv.home

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The drum's shape, from a row's distance out to the wheel's own arithmetic.
 *
 * This suite exists because the drum had two defects that only a measurement shows and an
 * eyeball does not. The fold damped itself with distance -- `(unfold * 1.6 - a * 0.18)`, which
 * reaches one about three rows out and then decays -- so a row further out than that was dragged
 * back onto its neighbour in the *resting* wheel: measured on a 970dp viewport, the rows four,
 * five and six landed at 352, 350 and 312 against a 100dp pitch, so row six was drawn behind row
 * four and the top of the drum was a flat, out-of-order smear. And the arc's radius was counted
 * in rows, so how curved the wheel looked depended on how many rows the screen happened to show.
 *
 * Everything here is in pixels and rows, the units the geometry itself works in, and a drum is
 * described by its radius: `rowPx` is one row and `halfSpanPx` is half a phone drum's viewport.
 */
class RandomWheelFaceTest {

    private val rowPx = 300f                 // a 100dp row at 3x
    private val halfSpanPx = 825f            // a 275dp half-viewport at 3x
    private val radiusPx = randomWheelRadiusPx(halfSpanPx)
    private val rowsAtEdge = halfSpanPx / rowPx

    private fun face(
        rowsOut: Float,
        unfold: Float = 1f,
        landing: Float = 0f,
        radius: Float = radiusPx,
    ) = randomWheelFace(
        distanceInRows = rowsOut,
        rowPx = rowPx,
        radiusPx = radius,
        unfold = unfold,
        landing = landing,
    )

    /** Where the row ends up on screen, relative to the centre slot's own centre. */
    private fun drawnCentreY(rowsOut: Float, unfold: Float = 1f) =
        rowsOut * rowPx + face(rowsOut, unfold = unfold).shiftYPx

    /**
     * How far round the drum the drum's own radius puts a row at the viewport's edge, in degrees.
     *
     * Read back through [asin] rather than asserted from the constant, because that is the claim
     * the drum makes about itself: the radius is derived from this angle, so dividing the other way
     * would confirm whatever the constant happens to be instead of measuring it.
     */
    private fun edgeAngleDeg(halfSpan: Float = halfSpanPx): Float =
        asin((halfSpan / randomWheelRadiusPx(halfSpan)).coerceIn(-1f, 1f)) * 180f / PI.toFloat()

    // ------------------------------------------------------------- the C ----

    @Test
    fun `the arc grows at every row out to the edge of the drum`() {
        val rows = listOf(0f, 0.25f, 0.5f, 1f, 1.5f, 2f, 2.5f, rowsAtEdge)
        val arcs = rows.map { face(it).arcPx }
        assertEquals(0f, arcs.first(), "the centred row sits on the cylinder, not beside it")
        arcs.zipWithNext { nearer, further ->
            assertTrue(further > nearer, "the arc stopped growing: $rows gave $arcs")
        }
    }

    @Test
    fun `the tilt grows with distance and mirrors either side of the centre`() {
        val rows = listOf(0.5f, 1f, 2f, rowsAtEdge)
        val above = rows.map { face(-it).tiltDeg }
        val below = rows.map { face(it).tiltDeg }
        assertEquals(0f, abs(face(0f).tiltDeg), "the centred row is upright, which is where it settles")
        above.zipWithNext { nearer, further ->
            assertTrue(further > nearer, "the tilt stopped growing towards the top: $above")
        }
        // Opposite signs: a barrel leans its top away and its bottom away, so the two halves
        // lean the same way on screen. The same sign on both would read as a bowl, not a C.
        rows.indices.forEach { i ->
            assertEquals(-above[i], below[i], 0.001f, "row ${rows[i]} out is not mirrored")
        }
    }

    @Test
    fun `no two rows inside the viewport can share a tilt or an arc`() {
        // The old tilt clamped at 85 degrees and the old arc at a fixed 120dp. Neither is
        // reachable on a phone and both bite on a taller drum, which is how the curve's ends
        // turn into flat ends. A quarter turn is the only cap, and no row inside the viewport
        // reaches it -- so the whole visible span is strictly monotonic in tilt and in arc.
        (0..20).forEach { step ->
            val rowsOut = rowsAtEdge * step / 20f
            val face = face(rowsOut)
            val next = face(rowsOut + 0.01f)
            assertTrue(
                abs(face.tiltDeg) < 90f,
                "row $rowsOut is tilted ${face.tiltDeg}, which is capped or past the tangent",
            )
            assertTrue(
                next.arcPx > face.arcPx,
                "rows $rowsOut and beyond share one arc of ${face.arcPx}: the curve has run out",
            )
            assertTrue(
                abs(next.tiltDeg) > abs(face.tiltDeg),
                "rows $rowsOut and beyond share one tilt of ${face.tiltDeg}",
            )
        }
    }

    // ------------------------------------------------------- the fold ------

    @Test
    fun `a resting wheel keeps the rows in order, so the top cannot collapse into a smear`() {
        // The defect, stated as a property rather than as a number: the old fold pulled far rows
        // inwards, hard enough to reverse them -- row six drawn at 312 while row four sat at 352,
        // so the top of the drum was three overlapping rows in the wrong order. A wheel whose rows
        // are in order cannot read as flat at the top.
        //
        // The sweep stops at the quarter turn, which is where the drum's own promise ends: past it
        // every row parks at the same place by design, and the test below is what holds them there
        // instead of letting them stack. The bound is the quarter turn rather than a row count
        // because the radius follows the tuned angle -- at 60 degrees it arrives at five rows out,
        // and a sweep past it would be asserting that rows do not share a position.
        val quarterTurnRows = (PI / 2.0).toFloat() * radiusPx / rowPx
        var previous = drawnCentreY(0f)
        (1..32).forEach { step ->
            val rowsOut = step / 32f * quarterTurnRows
            val drawn = drawnCentreY(rowsOut)
            assertTrue(
                drawn > previous,
                "row $rowsOut is drawn at $drawn, no further out than the row inside it at " +
                    "$previous: the fold is still damping the resting wheel",
            )
            previous = drawn
        }
    }

    @Test
    fun `rows past the quarter turn park on the drum's edge instead of stacking`() {
        // The tilt stops at a quarter turn, so a row further out than that shares the outermost
        // row's tilt. Every such row is drawn at the same place -- the cylinder's radius -- so
        // there has to be exactly one such place and it has to be off the drum, or a phone's
        // worth of rows piles up at the edge the eye reads as the curve's end.
        val quarterTurnRows = (PI / 2.0).toFloat() * radiusPx / rowPx
        val first = drawnCentreY(quarterTurnRows + 0.01f)
        assertTrue(
            first > halfSpanPx,
            "a capped row is drawn at $first, inside the ${halfSpanPx}px viewport: the quarter " +
                "turn is parking rows where they are still visible",
        )
        listOf(quarterTurnRows + 1f, quarterTurnRows + 4f, quarterTurnRows * 2f).forEach { rowsOut ->
            assertEquals(
                first,
                drawnCentreY(rowsOut),
                0.01f,
                "row $rowsOut is not parked with the rows at the quarter turn",
            )
        }
        assertEquals(
            0f,
            face(quarterTurnRows + 1f).alpha,
            "a capped row is faded, not gone",
        )
    }

    @Test
    fun `a resting row is drawn exactly where the cylinder puts it`() {
        // The resting shift is the cylinder's own spacing and nothing else. `sin` is concave,
        // so a row set out `a` rows from the centre belongs nearer the centre than the flat
        // list's pitch, and that is the whole of the correction -- which is the claim the old
        // `local` term broke, since it added a distance falloff on top of it.
        listOf(0.5f, 1f, 2f, rowsAtEdge).forEach { rowsOut ->
            val angle = rowsOut * rowPx / radiusPx
            assertEquals(
                radiusPx * sin(angle) - rowsOut * rowPx,
                face(rowsOut).shiftYPx,
                0.01f,
                "row $rowsOut out carries a shift the cylinder does not account for",
            )
        }
    }

    @Test
    fun `a closed wheel draws every row on the centre slot`() {
        listOf(1f, 2f, 4f, 6f).forEach { rowsOut ->
            listOf(rowsOut, -rowsOut).forEach { signed ->
                assertEquals(
                    0f,
                    drawnCentreY(signed, unfold = 0f),
                    0.01f,
                    "row $signed out is not folded onto the centre slot",
                )
            }
        }
        assertEquals(0f, face(1f, unfold = 0f).alpha, "a closed wheel shows nothing")
    }

    @Test
    fun `the wheel opens monotonically, so a row never passes another`() {
        // The open blends between the centre slot and the cylinder, so it cannot overshoot
        // past its own resting place and cannot leave a row behind it.
        var previous = drawnCentreY(3f, unfold = 0f)
        listOf(0.25f, 0.5f, 0.75f, 1f).forEach { open ->
            val drawn = drawnCentreY(3f, unfold = open)
            assertTrue(
                drawn > previous,
                "at open=$open the row is at $drawn, no further out than $previous",
            )
            previous = drawn
        }
    }

    // ---------------------------------------------------- the fade, kept ----

    @Test
    fun `a resting row fades with distance and nothing else`() {
        assertEquals(1f, face(0f).alpha, "the winner is opaque")
        listOf(1f, 2f, 3f, 4f).forEach { rowsOut ->
            assertEquals(1f - 0.2f * rowsOut, face(rowsOut).alpha, 0.001f, "row $rowsOut out")
        }
        assertEquals(0f, face(5f).alpha, "the fade still ends the drum at five rows out")
    }

    // ------------------------------------------------- the radius itself ----

    @Test
    fun `the visible face spans the same angle whatever the drum's height`() {
        // A radius counted in rows cannot do this: it bends harder the more rows the screen
        // shows, so a phone showing three rows and a tablet showing nine were two different
        // wheels. What has to hold is the angle the visible face spans.
        val tuned = edgeAngleDeg(275f)
        listOf(150f, 275f, 485f, 900f).forEach { halfSpan ->
            assertEquals(
                tuned,
                edgeAngleDeg(halfSpan),
                0.01f,
                "a ${halfSpan}px drum spans a different angle than a 275px one",
            )
        }
        assertTrue(
            tuned in 0f..90f,
            "the edge angle is ${tuned}deg, which is past a quarter turn: the outermost row is " +
                "capped and the radius no longer describes the drum",
        )
    }

    @Test
    fun `the visible face bends far enough round to read as a knob`() {
        // The angle is the tuning, and the tuning is the complaint: a shallower drum is not a
        // different wheel, it is a flat list with a slight lean, and every other test in this file
        // passes either way. So the number is pinned rather than left to taste.
        //
        // Measured on a Pixel 5 render as the left edge of each row's cover disc -- 60 degrees
        // puts them 44px, 118px and 230px from the settle line, and the rejected 30 degrees gave
        // 102px and 169px, which is the drum that was reported as going flat towards the top.
        assertEquals(60f, edgeAngleDeg(), 0.01f, "the drum's visible span was retuned")
        // And the same claim in the units the eye reads it in: each row one step out is carried
        // 15.6% of its own width round the drum, where the rejected 30 degrees carried it 9.1% --
        // enough of a step to see the curve, little enough that the rows still read as a list.
        assertEquals(
            0.156f,
            face(1f).arcPx / rowPx,
            0.002f,
            "a row one step out no longer sits far enough round the drum to read as a C",
        )
    }

    @Test
    fun `an unmeasured row is invisible rather than wrong`() {
        // `randomDistanceFromCenter` reports six rows for a row it has not measured, which has
        // to land on a face that costs nothing: no alpha, no scale a layer would divide by,
        // and no tilt past the tangent.
        val face = face(6f)
        assertEquals(0f, face.alpha, "an unmeasured row is drawn")
        assertTrue(face.scale > 0f, "scale is zero, which a graphics layer cannot divide by")
        assertTrue(abs(face.tiltDeg) <= 90f, "an unmeasured row is past the tangent")
    }
}

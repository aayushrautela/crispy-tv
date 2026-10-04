package com.crispy.tv.home

import com.crispy.tv.domain.home.HOME_RANDOM_MIN_RATING
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wheel's decisions that are arithmetic rather than layout.
 *
 * The face itself is `GraphicsLayerScope` maths and cannot be reached from here -- it needs a
 * measured `LazyListState` -- so what is under test is everything the wheel decides *before* it
 * asks Compose for a number: which word a media type shows, and how far and how long a spin
 * travels.
 */
class HomeRandomOverlayTest {

    // ------------------------------------------------------------ randomTypeLabel

    @Test
    fun `a movie reads as Movie whatever case or padding it arrives in`() {
        assertEquals("Movie", randomTypeLabel("movie"))
        assertEquals("Movie", randomTypeLabel("MOVIE"))
        assertEquals("Movie", randomTypeLabel("  Movie  "))
    }

    @Test
    fun `every show-ish type reads as Show`() {
        assertEquals("Show", randomTypeLabel("show"))
        assertEquals("Show", randomTypeLabel("tv"))
        assertEquals("Show", randomTypeLabel("series"))
        assertEquals("Show", randomTypeLabel("episode"))
    }

    @Test
    fun `anime reads as Show because TMDB has no anime genre`() {
        // The backend does emit `anime` -- `CachingHomeCatalogService.toCatalogType()` maps it
        // through untouched -- but it is an add-on concept. TMDB splits it into Animation or
        // Action & Adventure, so a row reading "Anime" would name a genre this catalogue does
        // not have. Only the word changes; `HomeRandomCandidate.type` still carries `anime`
        // to the details route.
        assertEquals("Show", randomTypeLabel("anime"))
        assertEquals("Show", randomTypeLabel("  ANIME "))
    }

    @Test
    fun `a type that is not movie or show is a movie mirroring the backend mapping`() {
        // `toCatalogType()`'s `else -> "movie"` covers the rest of the world, including a blank
        // type. Reading a blank as a third kind of thing would put a word on screen the backend
        // never meant, so the same rule is applied rather than guessed at.
        assertEquals("Movie", randomTypeLabel(""))
        assertEquals("Movie", randomTypeLabel("   "))
        assertEquals("Movie", randomTypeLabel("documentary"))
    }

    // ---------------------------------------------------------- randomSpinSteps

    @Test
    fun `a spin travels between one and two turns of the pool whatever the pool size`() {
        // Both bounds are load-bearing and neither is the interesting half. `turns` is clamped to
        // 8..20 so a two-item pool still reads as a spin, and the bonus is drawn from the pool
        // size so a fifty-item pool lands nearer the end of its travel than a two-item one.
        //
        // The band is asserted against the *clamps* rather than against a second call.
        // `randomSpinSteps` returns a total and nothing else, so recovering the base turns
        // from it means drawing a second total -- and this case used to compare one random
        // draw against another, which fails for most pairs and claims nothing about the
        // production function. Repeated draws, because one draw cannot see a bound that is
        // off by one.
        for (poolSize in listOf(0, 1, 2, 8, 19, 20, 21, 50, 1000)) {
            val bonusMax = poolSize.coerceAtLeast(2)
            repeat(64) {
                val steps = randomSpinSteps(poolSize, Random.Default)
                assertTrue(
                    steps in 9..(20 + bonusMax),
                    "poolSize=$poolSize gave $steps, outside 9..${20 + bonusMax}",
                )
            }
        }
    }

    @Test
    fun `a short pool still spins at least eight turns`() {
        // Without the clamp a one-item pool would travel two rows and read as a nudge.
        repeat(32) {
            assertTrue(randomSpinSteps(1, Random.Default) >= 9, "one-item pool under-rotated")
            assertTrue(randomSpinSteps(0, Random.Default) >= 9, "empty pool under-rotated")
        }
    }

    @Test
    fun `a long pool does not spin more than twenty turns of base travel`() {
        // The clamp is on the base, not the total: the bonus is still drawn from the pool size,
        // so a fifty-item pool travels up to seventy turns and that is intended. The floor is
        // `8 + 1` -- the same one the case above asserts -- and a twenty-turn floor would be a
        // claim this function does not make: `coerceIn(8, 20)` admits eight turns, and a bound
        // satisfied only by luck is the kind that passes until a draw disagrees.
        repeat(64) {
            val steps = randomSpinSteps(50, Random.Default)
            assertTrue(steps in 9..70, "fifty-item pool gave $steps")
            assertTrue(randomSpinSteps(1000, Random.Default) in 9..1020, "huge pool out of band")
        }
    }

    @Test
    fun `the same seed gives the same spin`() {
        assertEquals(
            randomSpinSteps(37, Random(4242)),
            randomSpinSteps(37, Random(4242)),
        )
    }

    // ----------------------------------------------------- randomSpinDurationMs

    @Test
    fun `duration grows with the step count`() {
        assertEquals(1700, randomSpinDurationMs(0))
        assertEquals(1988, randomSpinDurationMs(12))
        assertEquals(2996, randomSpinDurationMs(54))
    }

    @Test
    fun `duration is capped so a long pool cannot outstay three seconds`() {
        assertEquals(3000, randomSpinDurationMs(55))
        assertEquals(3000, randomSpinDurationMs(1000))
        assertTrue(randomSpinDurationMs(1000) <= 3000)
    }

    @Test
    fun `every spin this wheel can produce lands inside the duration cap`() {
        // The cap is only a real rule if the step counts it is capping are reachable, which is
        // what ties these two functions together -- neither is meaningful tested alone.
        for (poolSize in listOf(1, 12, 50)) {
            repeat(16) {
                val steps = randomSpinSteps(poolSize, Random.Default)
                val durationMs = randomSpinDurationMs(steps)
                assertTrue(
                    durationMs in 1700..3000,
                    "poolSize=$poolSize steps=$steps duration=$durationMs",
                )
            }
        }
    }

    // --------------------------------------------------------------- the threshold

    @Test
    fun `the wheel's rating floor is six on the ten point scale`() {
        // Ratings arrive as display strings formatted by `formatRating`, one decimal, null when
        // absent -- so this is the number the parsed value has to clear, and it is asserted
        // here because the overlay passes it straight through with no default of its own.
        assertEquals(6.0, HOME_RANDOM_MIN_RATING)
    }
}
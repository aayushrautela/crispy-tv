package com.crispy.tv.details

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The seed-colour cache reached `commonMain` with the [DetailsPalette] split, and this is
 * its first coverage. The split changed two things that are worth pinning: the cache stopped
 * being a `@Synchronized` access-order `LinkedHashMap` and became a `Mutex`-guarded
 * `mutableMapOf`, and the LRU order is now maintained by hand because a plain map cannot be
 * asked to drop its eldest entry.
 *
 * The cache is a `private object`, so there is no reset and a test cannot start from a known
 * state. Every case therefore begins with [primeSeedColorCache], which inserts far more keys
 * than the cap so that everything which existed beforehand has provably been evicted. That
 * makes each case independent of the order the class runs in, and the priming step itself is
 * asserted so the helper cannot quietly stop priming and leave every later case reading
 * whatever the previous one left behind.
 */
class DetailsSeedColorCacheTest {
    /** Distinct enough that no two entries can collide on a packed `ULong`. */
    private fun colorFor(index: Int): Color = Color(0xFF000000L + index)

    /**
     * Fills the cache past its cap so that only the last [expectedSurvivors] keys remain.
     * With a cap of 96, priming 200 distinct keys guarantees every key that existed before
     * the prime was called has been evicted.
     */
    private suspend fun primeSeedColorCache(times: Int = 200, prefix: String = "prime-") {
        repeat(times) { index ->
            cacheDetailsSeedColor("$prefix$index", colorFor(index))
        }
    }

    @Test
    fun `priming leaves only the last cap worth of keys`() = runTest {
        primeSeedColorCache()

        // The first primed key must be long gone, and the most recent must still be there.
        assertNull(
            "the cache holds 96 entries, so priming 200 must have evicted the first",
            cachedDetailsSeedColor("prime-0"),
        )
        assertNotNull(
            "the newest key must survive the prime",
            cachedDetailsSeedColor("prime-199"),
        )
    }

    @Test
    fun `a stored colour comes back exactly`() = runTest {
        primeSeedColorCache()
        val stored = Color(0xFF336699)
        cacheDetailsSeedColor("round-trip", stored)

        // Exact equality, not a tolerance: the cache stores the packed ULong and rebuilds the
        // Color from it, so anything less than an exact match would hide a packing change.
        assertEquals(stored, cachedDetailsSeedColor("round-trip"))
    }

    @Test
    fun `a blank or absent url is never cached`() = runTest {
        primeSeedColorCache()
        cacheDetailsSeedColor("   ", Color.Red)

        assertNull("an absent url has no colour", cachedDetailsSeedColor(null))
        assertNull("a blank url has no colour", cachedDetailsSeedColor(""))
        assertNull(
            "a whitespace url is blank and must not be looked up either",
            cachedDetailsSeedColor("   "),
        )
    }

    @Test
    fun `the cap is exactly ninety-six and the ninety-seventh key evicts the first`() = runTest {
        primeSeedColorCache()

        // Fill to exactly the cap. Each of these 96 inserts also evicts one primed key, so by
        // the end the cache holds precisely the keys inserted here and nothing older.
        repeat(96) { index -> cacheDetailsSeedColor("k$index", colorFor(index)) }
        for (index in 0 until 96) {
            assertNotNull("k$index is within the cap and must be present", cachedDetailsSeedColor("k$index"))
        }

        cacheDetailsSeedColor("k96", colorFor(96))

        assertNotNull("the newest key is always present", cachedDetailsSeedColor("k96"))
        assertNull(
            "one key past the cap the eldest is evicted, so the cap really is 96",
            cachedDetailsSeedColor("k0"),
        )
    }

    @Test
    fun `storing a key that is already present moves it to the end rather than to the front`() = runTest {
        primeSeedColorCache()

        // Fill the cap with a marker followed by 95 others, so the marker is the eldest.
        cacheDetailsSeedColor("a", Color.Red)
        repeat(95) { index -> cacheDetailsSeedColor("b$index", colorFor(index + 1)) }

        // Re-storing the eldest must refresh its recency, not leave it at the front.
        cacheDetailsSeedColor("a", Color.Blue)
        assertEquals(
            "re-storing replaces the value",
            Color.Blue,
            cachedDetailsSeedColor("a"),
        )

        // At the cap the re-store evicts nothing on its own, so the effect only shows on the
        // next insert. If the hit had been left at the front, that insert would evict "a"
        // itself; because it moved to the end, the victim is the new eldest instead.
        cacheDetailsSeedColor("c", Color.Green)

        assertNotNull("the refreshed key survives the next insert", cachedDetailsSeedColor("a"))
        assertNotNull("the newest key is present", cachedDetailsSeedColor("c"))
        assertNull(
            "the eldest of the other 95 is evicted instead of the refreshed key",
            cachedDetailsSeedColor("b0"),
        )
    }
}

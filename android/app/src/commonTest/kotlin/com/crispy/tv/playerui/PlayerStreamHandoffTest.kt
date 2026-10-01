package com.crispy.tv.playerui

import com.crispy.tv.addons.streams.AddonStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * [PlayerStreamHandoff]'s one-shot semantics, which had no coverage at all and
 * which is the only thing in the file that can be wrong.
 *
 * **The file is a process-wide `object` with a mutable map, and a singleton is
 * shared across every test class in the worker JVM** — the same property that
 * forces `companion object` and `@FixMethodOrder` on the Robolectric suites in
 * `androidHostTest`. This suite gets away without both because it is a `commonTest`
 * on the JVM's own runner rather than Robolectric's sandbox, and because **every
 * case here is order-independent by construction**: each one stashes under a key it
 * generated itself and never asserts on the size of the map. A case that needed to
 * assert "the map is empty afterwards" would be order-dependent, and that is the
 * shape to avoid rather than to fix afterwards.
 *
 * **The three properties worth pinning are the latch, the key's shape, and the
 * two blank-key answers being the same answer.** Everything else is a `Map` and is
 * the standard library's problem.
 */
class PlayerStreamHandoffTest {

    @Test
    fun aStashedStreamComesBackWithItsLookupId() {
        val key = PlayerStreamHandoff.stash(stream(id = "s1"), "trending.en.json")

        val consumed = PlayerStreamHandoff.consume(key)

        assertEquals("com.example.addon::s1", consumed?.first?.stableKey)
        assertEquals("trending.en.json", consumed?.second)
    }

    @Test
    fun consumingTwiceAnswersTheSecondTimeWithNothing() {
        // **The latch is the whole contract.** The key travels in a launch Intent,
        // and an Intent can be redelivered: a configuration change, a process
        // restart, a user backing out and returning. A handoff that survived a
        // second read would hand the player a stream for a picker the user has
        // already left, and the second read is the one that is wrong precisely
        // because the first was right. So `remove`, not `get`, and this case is the
        // only thing that distinguishes them.
        val key = PlayerStreamHandoff.stash(stream(), "lookup")

        assertEquals("com.example.addon::s1", PlayerStreamHandoff.consume(key)?.first?.stableKey)
        assertNull(PlayerStreamHandoff.consume(key))
    }

    @Test
    fun aKeyThatWasNeverStashedAnswersNothing() {
        assertNull(PlayerStreamHandoff.consume("never-stashed"))
    }

    @Test
    fun aNullOrBlankKeyAnswersNothingWithoutTouchingTheMap() {
        // The guard is `isNullOrBlank()`, so a key that never existed and a key that
        // is empty are the same answer. **The caller is `PlayerSessionViewModel`
        // reading a value out of an Intent extra that may be absent**, so `null` is
        // the ordinary case and the blank check is a courtesy for an extra that
        // exists but carries nothing.
        assertNull(PlayerStreamHandoff.consume(null))
        assertNull(PlayerStreamHandoff.consume(""))
        assertNull(PlayerStreamHandoff.consume("   "))
        assertNull(PlayerStreamHandoff.consume("\t\n"))
    }

    @Test
    fun everyStashGetsItsOwnKey() {
        // **The key is the only thing that crosses the process boundary, so two
        // concurrent pickers must not collide.** A `lowercase()` of the stream id
        // would satisfy a test that only checks "the key is derived from the
        // stream"; a random one is what makes a stale key impossible to guess and
        // two handoffs independent. The assertion is on distinctness, not on
        // format — the format is a UUID and `UserMutationIdsTest` is where a
        // *required* format is pinned, because here nothing reads it.
        val keys = (1..32).map { PlayerStreamHandoff.stash(stream(), "lookup-$it") }

        assertEquals(32, keys.toSet().size)
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun aKeyIsNotTheLookupIdSoOneHandoffCannotBeReplayedUnderAnotherIdentity() {
        // Recorded because it is the reason the key is random rather than derived:
        // **a key built from the stream's own identity would let a caller that
        // knows or guesses the stream id consume a handoff it never stashed.**
        val stashed = PlayerStreamHandoff.stash(stream(id = "known-id"), "trending")

        assertNotEquals("com.example.addon::known-id", stashed)
        assertNotEquals("trending", stashed)
        assertNull(PlayerStreamHandoff.consume("known-id"))
        // The real handoff is still there, so the failed guess changed nothing.
        assertEquals("com.example.addon::known-id", PlayerStreamHandoff.consume(stashed)?.first?.stableKey)
    }

    @Test
    fun twoLiveHandoffsDoNotSeeEachOther() {
        // The map is keyed, so this is `Map` behaviour — but it is the behaviour
        // the picker depends on, because two screens can each stash before either
        // player consumes, and the player is handed one key at a time.
        val first = PlayerStreamHandoff.stash(stream(id = "first"), "lookup-1")
        val second = PlayerStreamHandoff.stash(stream(id = "second"), "lookup-2")

        assertEquals("com.example.addon::second", PlayerStreamHandoff.consume(second)?.first?.stableKey)
        assertEquals("com.example.addon::first", PlayerStreamHandoff.consume(first)?.first?.stableKey)
        // Consuming out of order still left each one intact until its own consume.
        assertNull(PlayerStreamHandoff.consume(first))
    }

    /**
     * **Read the whole `data class` before writing a fixture, closing paren
     * included.** `AddonStream` has fourteen members and only three of them
     * default, so a fixture that names two of them and stops is a compile error
     * naming the third -- which is the cheap outcome. The expensive outcome is a
     * fixture that omits a member because the writer assumed it defaulted.
     */
    private fun stream(id: String = "s1"): AddonStream {
        val providerId = "com.example.addon"
        return AddonStream(
            providerId = providerId,
            providerName = "Example",
            name = "Stream $id",
            url = "https://example.test/$id.m3u8",
            stableKey = "$providerId::$id",
        )
    }
}

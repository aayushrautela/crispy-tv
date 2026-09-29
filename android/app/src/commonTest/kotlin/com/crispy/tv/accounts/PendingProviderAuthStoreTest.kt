package com.crispy.tv.accounts

import com.crispy.tv.platform.KeyValueStore
import com.crispy.tv.testing.FakeKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `PendingProviderAuthStore` is the one of the four moved declarations with
 * behaviour of its own, and it changed engine in the move: it read and wrote a
 * `SharedPreferences` handle and now reads and writes a [KeyValueStore]. The
 * rules worth pinning are that a `state` is redeemable exactly once, that a
 * half-written pair is not a pair, and that what goes in is what comes out
 * after trimming.
 */
class PendingProviderAuthStoreTest {
    @Test
    fun `what was put is what comes back`() {
        val store = FakeKeyValueStore()
        PendingProviderAuthStore(store).put("trakt", "state-1")

        assertEquals("trakt" to "state-1", PendingProviderAuthStore(store).peek())
    }

    @Test
    fun `the provider and state are trimmed on the way in`() {
        val store = FakeKeyValueStore()
        PendingProviderAuthStore(store).put("  trakt\n", "  state-1  ")

        assertEquals("trakt" to "state-1", PendingProviderAuthStore(store).peek())
    }

    @Test
    fun `an empty store has nothing to peek at`() {
        assertNull(PendingProviderAuthStore(FakeKeyValueStore()).peek())
    }

    @Test
    fun `consume returns the pair and then nothing`() {
        val store = FakeKeyValueStore()
        val subject = PendingProviderAuthStore(store)
        subject.put("trakt", "state-1")

        assertEquals("trakt" to "state-1", subject.consume())
        assertNull(subject.consume(), "a state may be redeemed exactly once")
        assertNull(subject.peek())
    }

    @Test
    fun `peek leaves the pair in place`() {
        val store = FakeKeyValueStore()
        val subject = PendingProviderAuthStore(store)
        subject.put("trakt", "state-1")

        assertEquals("trakt" to "state-1", subject.peek())
        assertEquals("trakt" to "state-1", subject.consume(), "peek must not consume")
    }

    @Test
    fun `a later put replaces the previous pair`() {
        val store = FakeKeyValueStore()
        val subject = PendingProviderAuthStore(store)
        subject.put("trakt", "state-1")
        subject.put("simkl", "state-2")

        assertEquals("simkl" to "state-2", subject.consume())
    }

    @Test
    fun `a provider without a state is not a pair`() {
        val store = FakeKeyValueStore()
        store.putString("provider", "trakt")

        assertNull(PendingProviderAuthStore(store).consume())
        assertNull(PendingProviderAuthStore(store).peek())
    }

    @Test
    fun `a state without a provider is not a pair`() {
        val store = FakeKeyValueStore()
        store.putString("state", "state-1")

        assertNull(PendingProviderAuthStore(store).consume())
    }

    @Test
    fun `a half-written pair is left in place for the write that completes it`() {
        val store = FakeKeyValueStore()
        store.putString("provider", "trakt")
        val subject = PendingProviderAuthStore(store)

        assertNull(subject.consume(), "a provider with no state is not redeemable")
        assertEquals(
            emptyList(),
            store.writes.drop(1),
            "consume must not clear a pair it refused to return",
        )

        store.putString("state", "state-1")
        assertEquals("trakt" to "state-1", subject.consume())
    }
}

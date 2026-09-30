package com.crispy.tv.home

import com.crispy.tv.platform.KeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The suppression store's own rules, and the codec it persists through.
 *
 * The store used to parse its payload with `org.json`; the codec below is the
 * portable replacement, and the on-disk format is deliberately unchanged, so
 * the legacy cases pin bytes `org.json` itself once wrote. Anything malformed
 * reads as no suppressions rather than failing the home load.
 */
class SuppressionStoreTest {

    private class MemoryStore : KeyValueStore {
        private val map = mutableMapOf<String, String>()

        override fun getString(key: String, defaultValue: String?): String? = map[key] ?: defaultValue

        override fun putString(key: String, value: String) {
            map[key] = value
        }

        override fun getBoolean(key: String, defaultValue: Boolean): Boolean = defaultValue

        override fun putBoolean(key: String, value: Boolean) = Unit

        override fun getInt(key: String, defaultValue: Int): Int = defaultValue

        override fun putInt(key: String, value: Int) = Unit

        override fun getFloat(key: String, defaultValue: Float): Float = defaultValue

        override fun putFloat(key: String, value: Float) = Unit

        override fun contains(key: String): Boolean = map.containsKey(key)

        override fun keys(): Set<String> = map.keys.toSet()

        override fun remove(key: String) {
            map.remove(key)
        }

        override fun clear() = map.clear()
    }

    private val backing = MemoryStore()
    private val store = ContinueWatchingSuppressionStore(backing)

    @Test
    fun `a fresh store reads as no suppressions`() {
        assertTrue(store.read().isEmpty())
    }

    @Test
    fun `a write survives a round trip`() {
        store.write(mapOf("tmdb:1" to 1_700_000_000_000L, "tmdb:2" to 1_700_000_000_001L))

        assertEquals(mapOf("tmdb:1" to 1_700_000_000_000L, "tmdb:2" to 1_700_000_000_001L), store.read())
    }

    @Test
    fun `writing nothing removes the key instead of persisting an empty object`() {
        store.write(mapOf("tmdb:1" to 1L))
        store.write(emptyMap())

        assertFalse(backing.contains(ContinueWatchingSuppressionStore.KEY_ITEM_SUPPRESSIONS))
        assertTrue(store.read().isEmpty())
    }

    @Test
    fun `the rendered payload is the flat object org-json wrote`() {
        store.write(mapOf("tmdb:1" to 1L, "tmdb:2" to 2L))

        assertEquals(
            "{\"tmdb:1\":1,\"tmdb:2\":2}",
            backing.getString(ContinueWatchingSuppressionStore.KEY_ITEM_SUPPRESSIONS),
        )
    }

    @Test
    fun `a legacy payload reads back`() {
        backing.putString(ContinueWatchingSuppressionStore.KEY_ITEM_SUPPRESSIONS, "{\"tmdb:1\":1717000000000,\"tmdb:9\":42}")

        assertEquals(mapOf("tmdb:1" to 1717000000000L, "tmdb:9" to 42L), store.read())
    }

    @Test
    fun `payloads with whitespace and reordered pairs read back`() {
        backing.putString(
            ContinueWatchingSuppressionStore.KEY_ITEM_SUPPRESSIONS,
            "{ \"b\" : 2 , \"a\" : 1 }",
        )

        assertEquals(mapOf("b" to 2L, "a" to 1L), store.read())
    }

    @Test
    fun `a key that needs escaping round trips`() {
        store.write(mapOf("weird\"key\\with\nnewline" to 7L))

        assertEquals(mapOf("weird\"key\\with\nnewline" to 7L), store.read())
    }

    @Test
    fun `zero and negative timestamps are not suppressions`() {
        backing.putString(ContinueWatchingSuppressionStore.KEY_ITEM_SUPPRESSIONS, "{\"a\":0,\"b\":-3,\"c\":9}")

        assertEquals(mapOf("c" to 9L), store.read())
    }

    @Test
    fun `malformed payloads read as no suppressions instead of throwing`() {
        listOf(
            null,
            "",
            "   ",
            "not json",
            "{\"a\":}",
            "{\"a\":\"x\"}",
            "{\"a\":1",
            "[1,2]",
        ).forEach { raw ->
            backing.clear()
            if (raw != null) backing.putString(ContinueWatchingSuppressionStore.KEY_ITEM_SUPPRESSIONS, raw)

            assertEquals(emptyMap(), store.read(), "payload: $raw")
        }
    }
}

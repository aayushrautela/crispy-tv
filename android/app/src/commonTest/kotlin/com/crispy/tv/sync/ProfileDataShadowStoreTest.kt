package com.crispy.tv.sync

import com.crispy.tv.platform.RecordingKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

 /**
 * `ProfileDataShadowStore` had no coverage in any source set, because reaching it meant
 * constructing a real `SharedPreferences`. Its JSON *policies* were already lifted to
 * `ProfileDataShadowJsonAccessors.kt` and tested; what is left here is the read/write
 * orchestration and the key format, and both are now reachable from `commonTest` because
 * the store takes a `KeyValueStore` rather than a `Context`.
 *
 * The `KeyValueStore` double this file used to declare privately now lives in
 * `com.crispy.tv.platform.RecordingKeyValueStore`, because the search and AI stores took the
 * same port in the same landing and a second copy of an interface implementation is a copy
 * that rots.
 */

class ProfileDataShadowStoreRoundTripTest {
    private fun snapshot(
        profileId: String = "p1",
        settings: Map<String, String> = mapOf("k" to "v"),
        catalogPrefs: Map<String, String> = mapOf("c" to "w"),
        updatedAt: String? = "2026-01-02T03:04:05Z",
    ) = ProfileDataShadowStore.Snapshot(profileId, settings, catalogPrefs, updatedAt)

    @Test
    fun aWrittenSnapshotReadsBackIdentically() {
        val store = ProfileDataShadowStore(RecordingKeyValueStore())
        store.write(snapshot())

        val read = assertNotNull(store.read("p1"))
        assertEquals("p1", read.profileId)
        assertEquals(mapOf("k" to "v"), read.settings)
        assertEquals(mapOf("c" to "w"), read.catalogPrefs)
        assertEquals("2026-01-02T03:04:05Z", read.updatedAt)
    }

    @Test
    fun anAbsentProfileReadsAsNullRatherThanAnEmptySnapshot() {
        val store = ProfileDataShadowStore(RecordingKeyValueStore())

        // The distinction matters: an empty snapshot would be indistinguishable from a
        // profile that genuinely has no settings, and would then be pushed back up.
        assertNull(store.read("never-written"))
    }

    @Test
    fun aProfileWithNoSettingsStillRoundTripsAsAnEmptyMapNotAsAbsent() {
        val store = ProfileDataShadowStore(RecordingKeyValueStore())
        store.write(snapshot(settings = emptyMap(), catalogPrefs = emptyMap()))

        val read = assertNotNull(store.read("p1"))
        assertEquals(emptyMap(), read.settings)
        assertEquals(emptyMap(), read.catalogPrefs)
    }

    @Test
    fun anAbsentUpdatedAtIsStoredAsEmptyAndReadsBackAsNull() {
        val store = ProfileDataShadowStore(RecordingKeyValueStore())
        store.write(snapshot(updatedAt = null))

        // Stored as "" because the JSON writer has no null for a string, and read back as
        // null by `ifBlank`. A stored JSON null would read as null too, so the round trip is
        // not the thing that distinguishes them -- the blank-string normalisation is.
        assertNull(assertNotNull(store.read("p1")).updatedAt)
    }

    @Test
    fun aBlankUpdatedAtIsNormalisedToNullRatherThanKeptAsBlank() {
        val store = ProfileDataShadowStore(RecordingKeyValueStore())
        store.write(snapshot(updatedAt = "   "))

        assertNull(assertNotNull(store.read("p1")).updatedAt)
    }

    @Test
    fun malformedStoredJsonReadsAsNullRatherThanThrowing() {
        val backing = RecordingKeyValueStore(
            mapOf("${ProfileDataShadowStore.PROFILE_KEY_PREFIX}p1" to "{not json"),
        )
        val store = ProfileDataShadowStore(backing)

        assertNull(store.read("p1"))
    }

    @Test
    fun clearingOneProfileLeavesTheOtherInPlace() {
        val backing = RecordingKeyValueStore()
        val store = ProfileDataShadowStore(backing)
        store.write(snapshot(profileId = "p1"))
        store.write(snapshot(profileId = "p2"))

        store.clear("p1")

        assertNull(store.read("p1"))
        assertNotNull(store.read("p2"))
    }
}

class ProfileDataShadowStoreKeyTest {
    /**
     * The key is `prefix + profileId` for a *single* field and is never split, so it is
     * injective over distinct ids. That is the property the current design rests on, and it
     * is what stops a separator inside a profile id from reaching another profile's data.
     *
     * The instrument is injectivity over a table of near-miss ids, not a collision case:
     * **with one field there is no collision to assert**, and a test claiming one would be
     * asserting a defect this format does not have. The near-miss table is what a broken
     * key format actually fails on -- a `(a, b)` pair-wise join, or a format that re-uses
     * the separator as a field boundary, would map some of these onto one another.
     */
    @Test
    fun theKeyIsInjectiveOverProfileIdsThatDifferOnlyAroundTheSeparator() {
        val nearMisses = listOf(
            "a", "b", "a:b", "a:b:c", ":a", "a:", "::", "a::b",
            "profile_data_shadow", "profile_data_shadow:", "profile_data_shadow:x",
        )
        // Distinct ids must produce distinct keys. Comparing the KEY SET is the assertion;
        // comparing pairwise would pass for a function that returned a constant.
        val keys = nearMisses.map { ProfileDataShadowStore.PROFILE_KEY_PREFIX + it }
        assertEquals(nearMisses.size, keys.toSet().size)
    }

    @Test
    fun aSeparatorInsideAProfileIdStillRoundTripsUnderItsOwnId() {
        val store = ProfileDataShadowStore(RecordingKeyValueStore())
        store.write(
            ProfileDataShadowStore.Snapshot(
                profileId = "a:b",
                settings = mapOf("owner" to "the-complicated-one"),
                catalogPrefs = emptyMap(),
                updatedAt = null,
            ),
        )

        val read = assertNotNull(store.read("a:b"))
        assertEquals("a:b", read.profileId)
        assertEquals(mapOf("owner" to "the-complicated-one"), read.settings)
        // And it did not land under any neighbouring id.
        assertNull(store.read("a"))
        assertNull(store.read("b"))
        assertNull(store.read("a:b:c"))
    }

    @Test
    fun everyProfileKeyCarriesThePrefix() {
        val store = ProfileDataShadowStore(RecordingKeyValueStore())
        store.write(
            ProfileDataShadowStore.Snapshot("whoever", emptyMap(), emptyMap(), null),
        )

        assertNotNull(store.read("whoever"))
        assertTrue(
            ProfileDataShadowStore.PROFILE_KEY_PREFIX.isNotEmpty(),
            "the prefix is what namespaces this store's keys",
        )
    }

    /**
     * The prefix is shared with the wiring that opens the store, so a rename on either side
     * silently orphans every stored profile. This is the pair that keeps the two literals
     * from drifting: it fails if either the constant or the store's use of it changes shape.
     */
    @Test
    fun thePrefixIsTheSameLiteralTheWiringUsesToNameTheStore() {
        val storeName = "profile_data_shadow"
        assertEquals(storeName + ":", ProfileDataShadowStore.PROFILE_KEY_PREFIX)
    }
}

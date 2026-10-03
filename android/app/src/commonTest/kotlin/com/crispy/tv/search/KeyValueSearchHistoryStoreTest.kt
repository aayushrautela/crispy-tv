package com.crispy.tv.search

import com.crispy.tv.platform.RecordingKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the decisions that used to sit behind `SharedPreferences`: input normalization, the
 * eight-entry limit, case-insensitive identity, and the malformed-storage answer.
 */
class KeyValueSearchHistoryStoreTest {
    @Test
    fun recordTrimsTheQueryAndPutsItBeforeExistingHistory() {
        val backing = backing("[\"older\",\"another\"]")
        val store = KeyValueSearchHistoryStore(backing)

        assertEquals(listOf("new query", "older", "another"), store.record("  new query  "))
        assertEquals(listOf("new query", "older", "another"), store.load())
    }

    @Test
    fun recordingAnExistingQueryRemovesItsCaseInsensitiveDuplicateBeforePrepending() {
        val store = KeyValueSearchHistoryStore(backing("[\"I\",\"older\",\"i\",\"last\"]"))

        assertEquals(listOf("i", "older", "last"), store.record(" i "))
    }

    @Test
    fun recordCapsHistoryAtEightEntries() {
        val existing = (1..8).joinToString(",") { "\"old$it\"" }
        val store = KeyValueSearchHistoryStore(backing("[$existing]"))

        assertEquals(
            listOf("new", "old1", "old2", "old3", "old4", "old5", "old6", "old7"),
            store.record("new"),
        )
    }

    @Test
    fun blankRecordReturnsTheExistingHistoryWithoutWriting() {
        val backing = backing("[\"kept\"]")
        val store = KeyValueSearchHistoryStore(backing)
        backing.calls.clear()

        assertEquals(listOf("kept"), store.record("   "))
        assertEquals(listOf("getString(recent_searches)"), backing.calls)
    }

    @Test
    fun loadSkipsBlankNullAndContainerEntriesAndKeepsTheFirstCaseVariant() {
        val store = KeyValueSearchHistoryStore(
            backing("[\"  First  \",null,{\"not\":\"a query\"},\"first\",\"second\"]"),
        )

        assertEquals(listOf("First", "second"), store.load())
    }

    @Test
    fun loadUsesLocaleInvariantIdentityAndStopsAtEightEntries() {
        val entries = listOf("I", "i") + (1..8).map { "query$it" }
        val store = KeyValueSearchHistoryStore(
            backing(entries.joinToString(",", prefix = "[", postfix = "]") { "\"$it\"" }),
        )

        assertEquals(
            listOf("I", "query1", "query2", "query3", "query4", "query5", "query6", "query7"),
            store.load(),
        )
    }

    @Test
    fun malformedOrNonArrayStorageReadsAsEmpty() {
        val malformed = KeyValueSearchHistoryStore(backing("{not json"))
        val nonArray = KeyValueSearchHistoryStore(backing("{\"query\":\"one\"}"))

        assertTrue(malformed.load().isEmpty())
        assertTrue(nonArray.load().isEmpty())
    }

    @Test
    fun removingAQueryPreservesOrderAndRemovingTheLastOneDeletesThePayload() {
        val backing = backing("[\"one\",\"two\",\"three\"]")
        val store = KeyValueSearchHistoryStore(backing)

        assertEquals(listOf("one", "three"), store.remove(" TWO "))
        assertEquals(listOf("three"), store.remove("one"))
        assertEquals(emptyList(), store.remove("three"))
        assertEquals(listOf("remove(recent_searches)"), backing.calls.takeLast(1))
        assertFalse(backing.values.containsKey("recent_searches"))
    }

    @Test
    fun clearRemovesThePayloadAndReturnsEmptyHistory() {
        val backing = backing("[\"one\"]")
        val store = KeyValueSearchHistoryStore(backing)

        assertEquals(emptyList(), store.clear())
        assertFalse(backing.values.containsKey("recent_searches"))
        assertTrue(backing.calls.contains("remove(recent_searches)"))
    }

    private fun backing(json: String): RecordingKeyValueStore =
        RecordingKeyValueStore(mapOf("recent_searches" to json))
}

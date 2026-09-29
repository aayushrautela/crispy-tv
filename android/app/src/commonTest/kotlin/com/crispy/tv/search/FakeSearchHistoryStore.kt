package com.crispy.tv.search

/**
 * An in-memory [SearchHistoryStore] that records what it was asked.
 *
 * The androidMain implementation is unreachable from a test — it reaches
 * `SharedPreferences` and `org.json` — which is the whole argument for the port
 * existing. This double answers with whatever list a test sets and returns the
 * new value from each mutating call, so a test can assert both what the viewmodel
 * published and what the store was told.
 */
internal class FakeSearchHistoryStore(
    initial: List<String> = emptyList(),
) : SearchHistoryStore {
    var stored: List<String> = initial
        private set

    /** Sets the starting list, standing in for what a previous session persisted. */
    fun seed(entries: List<String>) = apply { stored = entries }

    val recordCalls = mutableListOf<String>()
    val removeCalls = mutableListOf<String>()
    var loadCalls = 0
        private set
    var clearCalls = 0
        private set

    override fun load(): List<String> {
        loadCalls++
        return stored
    }

    override fun record(query: String): List<String> {
        recordCalls += query
        stored = (listOf(query) + stored).distinct()
        return stored
    }

    override fun remove(query: String): List<String> {
        removeCalls += query
        stored = stored - query
        return stored
    }

    override fun clear(): List<String> {
        clearCalls++
        stored = emptyList()
        return stored
    }
}

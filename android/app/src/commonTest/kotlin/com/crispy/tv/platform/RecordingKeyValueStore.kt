package com.crispy.tv.platform

/**
 * An in-memory [KeyValueStore] that records every call.
 *
 * It records the calls rather than only the values because the port's own contract says two
 * instances for the same backend must not observe each other's keys, so a test that only checked
 * a round trip would pass even if the class under test had reached into a *shared* preference
 * file. `calls` is how a test asks "what was written, and under which key" rather than "what does
 * the map look like now".
 *
 * **This is one class rather than one per suite because a double is a shape that rots.** It began
 * life as a `private` class inside `ProfileDataShadowStoreTest`, and it grew a second copy the
 * moment a second class wanted a `KeyValueStore` to test against. Two copies of a
 * [KeyValueStore] double are two places to forget that `putString`'s value is non-null and that
 * the numeric getters answer their default — the two details this interface is easiest to
 * misremember. Lifting it to a shared test declaration is the same move as making
 * `RecordingBackendApi` `open`, and it is why `search` and `ai` can test their stores without a
 * second implementation appearing later.
 *
 * `values` is a `LinkedHashMap` so a test that snapshots key order reads insertion order, which
 * is the order the class under test wrote in; the real port promises no order, so no assertion
 * should depend on it beyond reproducibility.
 */
internal class RecordingKeyValueStore(
    initial: Map<String, String> = emptyMap(),
) : KeyValueStore {
    val values = LinkedHashMap<String, String>(initial)
    val calls = mutableListOf<String>()

    override fun getString(key: String, defaultValue: String?): String? {
        calls += "getString($key)"
        return values[key] ?: defaultValue
    }

    override fun putString(key: String, value: String) {
        calls += "putString($key)"
        values[key] = value
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = defaultValue
    override fun putBoolean(key: String, value: Boolean) = Unit
    override fun getInt(key: String, defaultValue: Int): Int = defaultValue
    override fun putInt(key: String, value: Int) = Unit
    override fun getFloat(key: String, defaultValue: Float): Float = defaultValue
    override fun putFloat(key: String, value: Float) = Unit
    override fun contains(key: String): Boolean = values.containsKey(key)
    override fun keys(): Set<String> = values.keys
    override fun remove(key: String) {
        calls += "remove($key)"
        values.remove(key)
    }

    override fun clear() {
        calls += "clear()"
        values.clear()
    }
}
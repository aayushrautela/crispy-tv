package com.crispy.tv.settings

import com.crispy.tv.platform.KeyValueStore

/**
 * In-memory [KeyValueStore] for the settings tests.
 *
 * Counts every write so a test can assert that a setter which decides nothing
 * changed left the store alone -- the repositories all short-circuit in that
 * case, and that is behaviour worth pinning rather than a detail.
 */
internal class FakeKeyValueStore(
    initial: Map<String, Any> = emptyMap(),
) : KeyValueStore {
    private val values = initial.toMutableMap()

    val writes: MutableList<String> = mutableListOf()

    override fun getString(key: String, defaultValue: String?): String? =
        values[key] as? String ?: defaultValue

    override fun putString(key: String, value: String) {
        values[key] = value
        writes += key
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        values[key] as? Boolean ?: defaultValue

    override fun putBoolean(key: String, value: Boolean) {
        values[key] = value
        writes += key
    }

    override fun getInt(key: String, defaultValue: Int): Int =
        values[key] as? Int ?: defaultValue

    override fun putInt(key: String, value: Int) {
        values[key] = value
        writes += key
    }

    override fun getFloat(key: String, defaultValue: Float): Float =
        values[key] as? Float ?: defaultValue

    override fun putFloat(key: String, value: Float) {
        values[key] = value
        writes += key
    }

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun keys(): Set<String> = values.keys.toSet()

    override fun remove(key: String) {
        values.remove(key)
        writes += key
    }

    override fun clear() {
        values.clear()
        writes += "*clear*"
    }
}

package com.crispy.tv.accounts

import com.crispy.tv.platform.KeyValueStore

/**
 * An in-memory [KeyValueStore], so [ActiveProfileStore] can be driven from a test
 * on every target instead of only where `SharedPreferences` happens to exist.
 *
 * Counts reads, because `BackendContextResolver`'s cache is only observable
 * through how *often* it asks for the active profile id: a resolver that skipped
 * the store entirely would produce the same answers as one that used it.
 */
internal class FakeKeyValueStore : KeyValueStore {

    private val entries = mutableMapOf<String, String>()
    private val booleans = mutableMapOf<String, Boolean>()
    private val ints = mutableMapOf<String, Int>()
    private val floats = mutableMapOf<String, Float>()

    var getStringCalls: Int = 0
        private set

    override fun getString(key: String, defaultValue: String?): String? {
        getStringCalls++
        return entries[key] ?: defaultValue
    }

    override fun putString(key: String, value: String) {
        entries[key] = value
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = booleans[key] ?: defaultValue

    override fun putBoolean(key: String, value: Boolean) {
        booleans[key] = value
    }

    override fun getInt(key: String, defaultValue: Int): Int = ints[key] ?: defaultValue

    override fun putInt(key: String, value: Int) {
        ints[key] = value
    }

    override fun getFloat(key: String, defaultValue: Float): Float = floats[key] ?: defaultValue

    override fun putFloat(key: String, value: Float) {
        floats[key] = value
    }

    override fun contains(key: String): Boolean = entries.containsKey(key)

    override fun keys(): Set<String> = entries.keys.toSet()

    override fun remove(key: String) {
        entries.remove(key)
    }

    override fun clear() {
        entries.clear()
        booleans.clear()
        ints.clear()
        floats.clear()
    }
}

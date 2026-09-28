package com.crispy.tv.platform

/**
 * Small synchronous key/value persistence, the portable seam over
 * `SharedPreferences` on Android and `NSUserDefaults` on Apple platforms.
 *
 * Namespaced per store: two [KeyValueStore] instances for the same backend must
 * not observe each other's keys.
 */
interface KeyValueStore {
    fun getString(key: String, defaultValue: String? = null): String?
    fun putString(key: String, value: String)

    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean
    fun putBoolean(key: String, value: Boolean)

    fun getInt(key: String, defaultValue: Int = 0): Int
    fun putInt(key: String, value: Int)

    fun getFloat(key: String, defaultValue: Float = 0f): Float
    fun putFloat(key: String, value: Float)

    fun contains(key: String): Boolean

    /**
     * Every key currently stored, in no guaranteed order.
     *
     * Present because scanning a key prefix is a real requirement rather than a
     * convenience: `WatchProgressStore` keeps one entry per watched item under a
     * single prefix and has to enumerate them. `SharedPreferences.all` and
     * `NSUserDefaults.dictionaryRepresentation` both offer this; without it the
     * portable side would have to maintain a separate per-item index, which is
     * more state to keep consistent than a scan of keys that are already indexed.
     *
     * Callers must not depend on the order, and must re-read each value they need
     * rather than treating the key set as a snapshot of the data.
     */
    fun keys(): Set<String>

    fun remove(key: String)
    fun clear()
}

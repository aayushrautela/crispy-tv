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
    fun remove(key: String)
    fun clear()
}

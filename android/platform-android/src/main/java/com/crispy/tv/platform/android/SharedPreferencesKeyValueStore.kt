package com.crispy.tv.platform.android

import android.content.Context
import android.content.SharedPreferences
import com.crispy.tv.platform.KeyValueStore

/**
 * `KeyValueStore` over `SharedPreferences`.
 *
 * ## Namespacing
 *
 * The `KeyValueStore` contract says two instances for the same backend must not
 * observe each other's keys. That is enforced by giving every instance its own
 * `SharedPreferences` *file*, named after the store's [name], rather than by
 * prefixing keys inside one file. A key prefix would have to be re-applied on every
 * read and write, and a single missed prefix is a silent data leak between stores.
 * Separate files cannot leak, because the platform does the separation.
 *
 * The name is sanitised because it becomes part of a filename, and a caller
 * choosing a store name should not have to know that.
 */
class SharedPreferencesKeyValueStore(
    context: Context,
    name: String,
) : KeyValueStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(
            sanitize(name),
            Context.MODE_PRIVATE,
        )

    override fun getString(key: String, defaultValue: String?): String? =
        prefs.getString(key, defaultValue)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        prefs.getBoolean(key, defaultValue)

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun getInt(key: String, defaultValue: Int): Int =
        prefs.getInt(key, defaultValue)

    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    override fun getFloat(key: String, defaultValue: Float): Float =
        prefs.getFloat(key, defaultValue)

    override fun putFloat(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
    }

    override fun contains(key: String): Boolean = prefs.contains(key)

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        /** `SharedPreferences` rejects `/` and `\` in a file name. */
        fun sanitize(name: String): String =
            name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}

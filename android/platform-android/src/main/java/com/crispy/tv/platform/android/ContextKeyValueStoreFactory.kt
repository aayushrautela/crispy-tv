package com.crispy.tv.platform.android

import android.content.Context
import com.crispy.tv.platform.KeyValueStore
import com.crispy.tv.platform.KeyValueStoreFactory

/**
 * The Android [KeyValueStoreFactory]: one `SharedPreferences` file per store name.
 *
 * The name is sanitised by [SharedPreferencesKeyValueStore] itself, so this class does not repeat
 * that decision — the same rule that keeps a name from escaping its own file, applied once.
 *
 * Constructed with the **application** context: a store built from an activity context outlives
 * nothing useful and leaks the activity, and `SharedPreferences` are already process-wide.
 */
class ContextKeyValueStoreFactory(context: Context) : KeyValueStoreFactory {
    private val appContext = context.applicationContext

    private val stores = mutableMapOf<String, KeyValueStore>()

    override fun store(name: String): KeyValueStore = stores.getOrPut(name) {
        SharedPreferencesKeyValueStore(appContext, name)
    }
}
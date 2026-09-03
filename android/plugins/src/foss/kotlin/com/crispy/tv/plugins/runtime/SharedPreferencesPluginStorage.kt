package com.crispy.tv.plugins.runtime

import android.content.Context
import android.content.SharedPreferences

internal class SharedPreferencesPluginStorage(context: Context) : PluginStorage {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun get(pluginId: String, key: String): String? {
        return prefs.getString(storageKey(pluginId, key), null)
    }

    override fun set(pluginId: String, key: String, value: String) {
        prefs.edit().putString(storageKey(pluginId, key), value).apply()
    }

    override fun clear(pluginId: String) {
        val prefix = storagePrefix(pluginId)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        editor.apply()
    }

    private fun storageKey(pluginId: String, key: String): String = storagePrefix(pluginId) + key.trim()

    private fun storagePrefix(pluginId: String): String = "$pluginId::"

    private companion object {
        const val PREFS_NAME = "crispy_plugin_storage"
    }
}

package com.crispy.tv.plugins.runtime

internal interface PluginStorage {
    fun get(pluginId: String, key: String): String?

    fun set(pluginId: String, key: String, value: String)

    fun clear(pluginId: String)
}

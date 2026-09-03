package com.crispy.tv.plugins.runtime

internal interface PluginBridges {
    fun log(pluginId: String, message: String)

    suspend fun fetch(requestJson: String): String

    fun storageGet(pluginId: String, key: String): String?

    fun storageSet(pluginId: String, key: String, value: String)
}

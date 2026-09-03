package com.crispy.tv.plugins.runtime

internal object PluginRuntimeProvider {
    fun create(bridges: PluginBridges): PluginRuntime = QuickJsPluginRuntime(bridges)
}

package com.crispy.tv.plugins.runtime

object PluginRuntimeProvider {
    fun create(bridges: PluginBridges): PluginRuntime = QuickJsPluginRuntime(bridges)
}

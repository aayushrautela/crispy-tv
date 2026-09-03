package com.crispy.tv.plugins.runtime

import com.crispy.tv.plugins.PluginExecutionResult
import com.crispy.tv.plugins.PluginStreamInput

fun interface PluginRuntime {
    suspend fun getStreams(pluginId: String, code: String, input: PluginStreamInput): PluginExecutionResult
}

package com.crispy.tv.plugins.runtime

import com.crispy.tv.plugins.PluginExecutionResult
import com.crispy.tv.plugins.PluginStreamInput
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.evaluate
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

internal class QuickJsPluginRuntime(
    private val bridges: PluginBridges,
    private val executionTimeoutMs: Long = DEFAULT_EXECUTION_TIMEOUT_MS,
) : PluginRuntime {

    override suspend fun getStreams(
        pluginId: String,
        code: String,
        input: PluginStreamInput,
    ): PluginExecutionResult {
        return try {
            val raw = withTimeout(executionTimeoutMs) {
                quickJs(jobDispatcher = Dispatchers.Default) {
                    installHostBindings(pluginId)
                    evaluate<Any?>(
                        buildScript(pluginId, code, input),
                        filename = "plugin-$pluginId.js",
                    )
                }
            }
            PluginResultMapper.result(
                streams = PluginResultMapper.mapStreams(raw),
                settings = emptyList(),
            )
        } catch (error: TimeoutCancellationException) {
            failure(pluginId, "Timed out after $executionTimeoutMs ms")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failure(pluginId, "Script error: ${error.message}")
        }
    }

    private fun com.dokar.quickjs.QuickJs.installHostBindings(pluginId: String) {
        define("crispy") {
            function("__log") { args ->
                bridges.log(pluginId, args.firstOrNull()?.toString().orEmpty())
            }
            asyncFunction("__fetch") { args ->
                bridges.fetch(args.firstOrNull()?.toString().orEmpty())
            }
            function("__storageGet") { args ->
                bridges.storageGet(pluginId, args.firstOrNull()?.toString().orEmpty())
            }
            function("__storageSet") { args ->
                if (args.size >= 2) {
                    bridges.storageSet(
                        pluginId,
                        args[0]?.toString().orEmpty(),
                        args[1]?.toString().orEmpty(),
                    )
                }
            }
        }
    }

    private fun buildScript(
        pluginId: String,
        code: String,
        input: PluginStreamInput,
    ): String {
        val inputJson = PluginInputJson.encode(input)
        return """
            |const __crispyHost = {
            |  log: (...messages) => crispy.__log(messages.map(String).join(' ')),
            |  fetch: async (input) => {
            |    const options = typeof input === 'string' ? { url: input } : input;
            |    return JSON.parse(await crispy.__fetch(JSON.stringify(options)));
            |  },
            |  storageGet: (key) => crispy.__storageGet(key),
            |  storageSet: (key, value) => crispy.__storageSet(key, String(value)),
            |};
            |$code
            |if (typeof getStreams !== 'function') {
            |  throw new Error('Plugin must define getStreams()');
            |}
            |const __result = await getStreams($inputJson);
            |__result;
        """.trimMargin()
    }

    private fun failure(pluginId: String, reason: String): PluginExecutionResult {
        bridges.log(pluginId, "getStreams failed: $reason")
        return PluginResultMapper.result(streams = emptyList(), settings = emptyList())
    }

    private companion object {
        const val DEFAULT_EXECUTION_TIMEOUT_MS = 60_000L
    }
}

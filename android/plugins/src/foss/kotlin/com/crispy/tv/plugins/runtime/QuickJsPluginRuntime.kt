package com.crispy.tv.plugins.runtime

import com.crispy.tv.plugins.PluginExecutionResult
import com.crispy.tv.plugins.PluginJsArgs
import com.crispy.tv.plugins.PluginStreamInput
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
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
                streams = PluginResultMapper.mapStreams(raw).also { mapped ->
                    val rawCount = (raw as? List<*>)?.size ?: 0
                    android.util.Log.i(
                        LOG_TAG,
                        "[plugin:$pluginId] script returned $rawCount raw row(s), ${mapped.size} mapped stream(s)",
                    )
                },
                settings = emptyList(),
            )
        } catch (error: TimeoutCancellationException) {
            android.util.Log.w(LOG_TAG, "[plugin:$pluginId] timed out after $executionTimeoutMs ms")
            failure(pluginId, "Timed out after $executionTimeoutMs ms")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            android.util.Log.w(
                LOG_TAG,
                "[plugin:$pluginId] script failed: ${error::class.simpleName}: ${error.message}",
            )
            failure(pluginId, "Script error: ${error.message}")
        }
    }

    private fun QuickJs.installHostBindings(pluginId: String) {
        function("__crispyLog") { args ->
            bridges.log(pluginId, args.firstOrNull()?.toString().orEmpty())
        }
        asyncFunction("__crispyFetch") { args ->
            bridges.fetch(args.firstOrNull()?.toString().orEmpty())
        }
        function("__crispyStorageGet") { args ->
            bridges.storageGet(pluginId, args.firstOrNull()?.toString().orEmpty())
        }
        function("__crispyStorageSet") { args ->
            if (args.size >= 2) {
                bridges.storageSet(
                    pluginId,
                    args[0]?.toString().orEmpty(),
                    args[1]?.toString().orEmpty(),
                )
            }
        }
        function("__crispyDomLoad") { args ->
            bridges.domLoad(pluginId, args.firstOrNull()?.toString().orEmpty())
        }
        function("__crispyDomSelect") { args ->
            bridges.domSelect(
                pluginId,
                args.getOrNull(0)?.toString().orEmpty(),
                args.getOrNull(1)?.toString().orEmpty(),
            )
        }
        function("__crispyDomFind") { args ->
            bridges.domFind(
                pluginId,
                args.getOrNull(0)?.toString().orEmpty(),
                args.getOrNull(1)?.toString().orEmpty(),
                args.getOrNull(2)?.toString().orEmpty(),
            )
        }
        function("__crispyDomText") { args ->
            bridges.domText(
                pluginId,
                args.getOrNull(0)?.toString().orEmpty(),
                args.getOrNull(1)?.toString().orEmpty(),
            )
        }
        function("__crispyDomInnerHtml") { args ->
            bridges.domInnerHtml(
                pluginId,
                args.getOrNull(0)?.toString().orEmpty(),
                args.getOrNull(1)?.toString().orEmpty(),
            )
        }
        function("__crispyDomHtml") { args ->
            bridges.domHtml(
                pluginId,
                args.getOrNull(0)?.toString().orEmpty(),
                args.getOrNull(1)?.toString().orEmpty(),
            )
        }
        function("__crispyDomAttr") { args ->
            bridges.domAttr(
                pluginId,
                args.getOrNull(0)?.toString().orEmpty(),
                args.getOrNull(1)?.toString().orEmpty(),
                args.getOrNull(2)?.toString().orEmpty(),
            )
        }
        function("__crispyDomNext") { args ->
            bridges.domNext(
                pluginId,
                args.getOrNull(0)?.toString().orEmpty(),
                args.getOrNull(1)?.toString().orEmpty(),
            )
        }
        function("__crispyDomPrev") { args ->
            bridges.domPrev(
                pluginId,
                args.getOrNull(0)?.toString().orEmpty(),
                args.getOrNull(1)?.toString().orEmpty(),
            )
        }
        function("__crispyDigestHex") { args ->
            bridges.digestHex(
                pluginId,
                args.getOrNull(0)?.toString().orEmpty(),
                args.getOrNull(1)?.toString().orEmpty(),
            )
        }
        function("__crispyHmacHex") { args ->
            bridges.hmacHex(
                pluginId,
                args.getOrNull(0)?.toString().orEmpty(),
                args.getOrNull(1)?.toString().orEmpty(),
                args.getOrNull(2)?.toString().orEmpty(),
            )
        }
        function("__crispyPbkdf2Hex") { args ->
            bridges.pbkdf2Hex(
                pluginId,
                args.getOrNull(0)?.toString().orEmpty(),
                args.getOrNull(1)?.toString().orEmpty(),
                (args.getOrNull(2) as? Number)?.toInt() ?: 0,
                (args.getOrNull(3) as? Number)?.toInt() ?: 0,
                args.getOrNull(4)?.toString().orEmpty(),
            )
        }
        function("__crispyAesEncryptHex") { args ->
            bridges.aesEncryptHex(
                pluginId,
                args.getOrNull(0)?.toString().orEmpty(),
                args.getOrNull(1)?.toString().orEmpty(),
                args.getOrNull(2)?.toString().orEmpty(),
                args.getOrNull(3)?.toString().orEmpty(),
            )
        }
        function("__crispyAesDecryptHex") { args ->
            bridges.aesDecryptHex(
                pluginId,
                args.getOrNull(0)?.toString().orEmpty(),
                args.getOrNull(1)?.toString().orEmpty(),
                args.getOrNull(2)?.toString().orEmpty(),
                args.getOrNull(3)?.toString().orEmpty(),
            )
        }
        function("__crispyUtf8ToHex") { args ->
            bridges.utf8ToHex(pluginId, args.firstOrNull()?.toString().orEmpty())
        }
        function("__crispyUtf8ToBytesJson") { args ->
            bridges.utf8BytesJson(pluginId, args.firstOrNull()?.toString().orEmpty())
        }
        function("__crispyBytesToUtf8") { args ->
            bridges.hexToUtf8(pluginId, args.firstOrNull()?.toString().orEmpty())
        }
        function("__crispyRandomHex") { args ->
            bridges.randomHex(
                pluginId,
                (args.firstOrNull() as? Number)?.toInt() ?: 0,
            )
        }
        function("__crispyBase64EncodeHex") { args ->
            bridges.base64EncodeHex(pluginId, args.firstOrNull()?.toString().orEmpty())
        }
        function("__crispyBase64DecodeHex") { args ->
            bridges.base64DecodeHex(pluginId, args.firstOrNull()?.toString().orEmpty())
        }
        function("__crispyBase64EncodeText") { args ->
            bridges.base64EncodeText(pluginId, args.firstOrNull()?.toString().orEmpty())
        }
        function("__crispyBase64DecodeText") { args ->
            bridges.base64DecodeText(pluginId, args.firstOrNull()?.toString().orEmpty())
        }
        function("__crispyParseUrl") { args ->
            bridges.parseUrl(pluginId, args.firstOrNull()?.toString().orEmpty())
        }
        function("__crispyResolve") { args ->
            bridges.resolveUrl(
                pluginId,
                args.getOrNull(0)?.toString().orEmpty(),
                args.getOrNull(1)?.toString().orEmpty(),
            )
        }
    }

    private fun buildScript(
        pluginId: String,
        code: String,
        input: PluginStreamInput,
    ): String {
        val args = PluginJsArgs.callArguments(input)
        val facade = PluginJsFacade.build(
            scraperIdJson = PluginJsArgs.string(pluginId),
            settingsJson = "{}",
        )
        return """
            |$facade
            |
            |var module = { exports: {} };
            |var exports = module.exports;
            |(function() {
            |$code
            |if (typeof getStreams !== 'undefined' && module.exports.getStreams === undefined) {
            |  module.exports.getStreams = getStreams;
            |}
            |})();
            |
            |var __getStreams = module.exports.getStreams || globalThis.getStreams;
            |var __result = [];
            |if (typeof __getStreams === 'function') {
            |  __result = await __getStreams($args);
            |} else {
            |  __crispyLog('getStreams function not found on module.exports or globalThis');
            |}
            |__result;
        """.trimMargin()
    }

    private fun failure(pluginId: String, reason: String): PluginExecutionResult {
        bridges.log(pluginId, "getStreams failed: $reason")
        return PluginResultMapper.result(streams = emptyList(), settings = emptyList())
    }

    private companion object {
        const val DEFAULT_EXECUTION_TIMEOUT_MS = 60_000L
        const val LOG_TAG = "CrispyPlugins"
    }
}

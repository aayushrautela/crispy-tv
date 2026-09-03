package com.crispy.tv.plugins.runtime

import com.crispy.tv.plugins.bridge.CryptoBridge
import com.crispy.tv.plugins.bridge.DomBridge
import com.crispy.tv.plugins.bridge.HexCodec
import com.crispy.tv.plugins.bridge.HttpBridge
import com.crispy.tv.plugins.bridge.PluginExecutionBlockedException
import com.crispy.tv.plugins.bridge.UrlBridge
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

internal class HostPluginBridges(
    okHttpClient: OkHttpClient,
    private val storage: PluginStorage,
) : PluginBridges() {

    private val httpBridge = HttpBridge(okHttpClient)
    private val random = SecureRandom()
    private val domBridges = ConcurrentHashMap<String, DomBridge>()

    override fun log(pluginId: String, message: String) {
        android.util.Log.w(LOG_TAG, "[plugin:$pluginId] ${message.take(500)}")
    }

    override suspend fun fetch(requestJson: String): String {
        return try {
            httpBridge.fetch(requestJson)
        } catch (error: PluginExecutionBlockedException) {
            throw error
        } catch (error: Exception) {
            writeFetchFailure(error.message ?: "fetch failed")
        }
    }

    override fun domLoad(pluginId: String, html: String): String {
        return domBridgeFor(pluginId).load(html)
    }

    override fun domSelect(pluginId: String, documentId: String, selector: String): String {
        return domBridgeFor(pluginId).select(documentId, selector)
    }

    override fun domFind(pluginId: String, documentId: String, elementId: String, selector: String): String {
        return domBridgeFor(pluginId).find(documentId, elementId, selector)
    }

    override fun domText(pluginId: String, documentId: String, elementIdsJson: String): String {
        return domBridgeFor(pluginId).text(documentId, parseIds(elementIdsJson))
    }

    override fun domInnerHtml(pluginId: String, documentId: String, elementId: String): String {
        return domBridgeFor(pluginId).innerHtml(documentId, elementId)
    }

    override fun domAttr(pluginId: String, documentId: String, elementId: String, name: String): String {
        return domBridgeFor(pluginId).attr(documentId, elementId, name)
    }

    override fun domNext(pluginId: String, documentId: String, elementId: String): String {
        return domBridgeFor(pluginId).next(documentId, elementId)
    }

    override fun domPrev(pluginId: String, documentId: String, elementId: String): String {
        return domBridgeFor(pluginId).prev(documentId, elementId)
    }

    override fun disposeDocument(pluginId: String, documentId: String) {
        domBridgeFor(pluginId).dispose(documentId)
    }

    override fun digestHex(pluginId: String, algorithm: String, dataHex: String): String {
        return CryptoBridge.digestHex(algorithm, dataHex)
    }

    override fun hmacHex(pluginId: String, algorithm: String, keyHex: String, dataHex: String): String {
        return CryptoBridge.hmacHex(algorithm, keyHex, dataHex)
    }

    override fun aesEncryptHex(pluginId: String, mode: String, keyHex: String, ivHex: String, dataHex: String): String {
        return CryptoBridge.aesEncryptHex(mode, keyHex, ivHex, dataHex)
    }

    override fun aesDecryptHex(pluginId: String, mode: String, keyHex: String, ivHex: String, dataHex: String): String {
        return CryptoBridge.aesDecryptHex(mode, keyHex, ivHex, dataHex)
    }

    override fun utf8ToHex(pluginId: String, text: String): String = CryptoBridge.utf8ToHex(text)

    override fun utf8BytesJson(pluginId: String, text: String): String = CryptoBridge.utf8BytesJson(text)

    override fun hexToUtf8(pluginId: String, hex: String): String = CryptoBridge.hexToUtf8(hex)

    override fun randomHex(pluginId: String, byteCount: Int): String {
        val count = byteCount.coerceIn(0, 1024)
        val bytes = ByteArray(count)
        random.nextBytes(bytes)
        return com.crispy.tv.plugins.bridge.HexCodec.encode(bytes)
    }

    override fun base64EncodeHex(pluginId: String, hex: String): String = CryptoBridge.base64EncodeHex(hex)

    override fun base64DecodeHex(pluginId: String, base64: String): String = CryptoBridge.base64DecodeHex(base64)

    override fun base64EncodeText(pluginId: String, text: String): String = CryptoBridge.base64EncodeText(text)

    override fun base64DecodeText(pluginId: String, base64: String): String = CryptoBridge.base64DecodeText(base64)

    override fun parseUrl(pluginId: String, urlString: String): String = UrlBridge.parseJson(urlString)

    override fun resolveUrl(pluginId: String, base: String, relative: String): String = UrlBridge.resolve(base, relative)

    override fun storageGet(pluginId: String, key: String): String? = storage.get(pluginId, key)

    override fun storageSet(pluginId: String, key: String, value: String) = storage.set(pluginId, key, value)

    private fun domBridgeFor(pluginId: String): DomBridge =
        domBridges.getOrPut(pluginId) { DomBridge() }

    private fun writeFetchFailure(message: String): String {
        return org.json.JSONObject()
            .put("status", 0)
            .put("headers", org.json.JSONObject())
            .put("bodyBase64", "")
            .put("body", "")
            .put("error", message)
            .toString()
    }

    private fun parseIds(elementIdsJson: String): List<String> {
        val array = org.json.JSONArray(elementIdsJson)
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotEmpty() }?.let(::add)
            }
        }
    }

    private companion object {
        const val LOG_TAG = "CrispyPlugins"
    }
}

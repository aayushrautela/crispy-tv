package com.crispy.tv.plugins.runtime

internal open class PluginBridges {

    open fun log(pluginId: String, message: String) {}

    open suspend fun fetch(requestJson: String): String {
        throw UnsupportedOperationException("fetch is unavailable")
    }

    open fun domLoad(pluginId: String, html: String): String {
        throw UnsupportedOperationException("dom is unavailable")
    }

    open fun domSelect(pluginId: String, documentId: String, selector: String): String {
        throw UnsupportedOperationException("dom is unavailable")
    }

    open fun domFind(pluginId: String, documentId: String, elementId: String, selector: String): String {
        throw UnsupportedOperationException("dom is unavailable")
    }

    open fun domText(pluginId: String, documentId: String, elementIdsJson: String): String {
        throw UnsupportedOperationException("dom is unavailable")
    }

    open fun domInnerHtml(pluginId: String, documentId: String, elementId: String): String {
        throw UnsupportedOperationException("dom is unavailable")
    }

    open fun domAttr(pluginId: String, documentId: String, elementId: String, name: String): String {
        throw UnsupportedOperationException("dom is unavailable")
    }

    open fun domNext(pluginId: String, documentId: String, elementId: String): String {
        throw UnsupportedOperationException("dom is unavailable")
    }

    open fun domPrev(pluginId: String, documentId: String, elementId: String): String {
        throw UnsupportedOperationException("dom is unavailable")
    }

    open fun disposeDocument(pluginId: String, documentId: String) {}

    open fun digestHex(pluginId: String, algorithm: String, dataHex: String): String {
        throw UnsupportedOperationException("crypto is unavailable")
    }

    open fun hmacHex(pluginId: String, algorithm: String, keyHex: String, dataHex: String): String {
        throw UnsupportedOperationException("crypto is unavailable")
    }

    open fun aesEncryptHex(pluginId: String, mode: String, keyHex: String, ivHex: String, dataHex: String): String {
        throw UnsupportedOperationException("crypto is unavailable")
    }

    open fun aesDecryptHex(pluginId: String, mode: String, keyHex: String, ivHex: String, dataHex: String): String {
        throw UnsupportedOperationException("crypto is unavailable")
    }

    open fun utf8ToHex(pluginId: String, text: String): String {
        throw UnsupportedOperationException("crypto is unavailable")
    }

    open fun utf8BytesJson(pluginId: String, text: String): String {
        throw UnsupportedOperationException("crypto is unavailable")
    }

    open fun hexToUtf8(pluginId: String, hex: String): String {
        throw UnsupportedOperationException("crypto is unavailable")
    }

    open fun randomHex(pluginId: String, byteCount: Int): String {
        throw UnsupportedOperationException("crypto is unavailable")
    }

    open fun base64EncodeHex(pluginId: String, hex: String): String {
        throw UnsupportedOperationException("crypto is unavailable")
    }

    open fun base64DecodeHex(pluginId: String, base64: String): String {
        throw UnsupportedOperationException("crypto is unavailable")
    }

    open fun base64EncodeText(pluginId: String, text: String): String {
        throw UnsupportedOperationException("crypto is unavailable")
    }

    open fun base64DecodeText(pluginId: String, base64: String): String {
        throw UnsupportedOperationException("crypto is unavailable")
    }

    open fun parseUrl(pluginId: String, urlString: String): String {
        throw UnsupportedOperationException("url is unavailable")
    }

    open fun resolveUrl(pluginId: String, base: String, relative: String): String {
        throw UnsupportedOperationException("url is unavailable")
    }

    open fun storageGet(pluginId: String, key: String): String? = null

    open fun storageSet(pluginId: String, key: String, value: String) {}
}

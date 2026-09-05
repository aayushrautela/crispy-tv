package com.crispy.tv.plugins.bridge

internal object UrlBridge {

    fun parseJson(urlString: String): String {
        val url =
            try {
                java.net.URI(urlString)
            } catch (error: Exception) {
                java.net.URI("")
            }
        val raw = urlString
        val scheme = url.scheme.orEmpty().lowercase()
        val host = url.host.orEmpty().lowercase()
        val port = if (url.port == -1) "" else url.port.toString()
        return org.json.JSONObject()
            .put("protocol", if (scheme.isEmpty()) "" else "$scheme:")
            .put("host", if (port.isEmpty()) host else "$host:$port")
            .put("hostname", host)
            .put("port", port)
            .put("pathname", url.rawPath?.ifEmpty { "/" } ?: "/")
            .put("search", url.rawQuery?.let { "?$it" } ?: "")
            .put("hash", url.rawFragment?.let { "#$it" } ?: "")
            .toString()
    }

    fun resolve(base: String, relative: String): String {
        return try {
            java.net.URI(base).resolve(relative).toString()
        } catch (error: Exception) {
            ""
        }
    }

    fun encode(text: String): String = java.net.URLEncoder.encode(text, "UTF-8")

    fun decode(text: String): String = java.net.URLDecoder.decode(text, "UTF-8")
}
